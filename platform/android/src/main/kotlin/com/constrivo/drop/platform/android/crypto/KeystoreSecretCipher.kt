package com.constrivo.drop.platform.android.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Where the Keystore keeps the wrapping key, as reported by `KeyInfo.getSecurityLevel()`. */
enum class KeySecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }

/**
 * [SecretCipher] on a non-exportable AES-256-GCM key in the Android Keystore (spec change N11, decision 7).
 *
 * The key is created on first use under [alias] with purposes encrypt and decrypt, GCM without padding, randomised
 * encryption (the Keystore picks every IV) and no user authentication, so the transfer service can read the identity
 * while the screen is locked. It lives in StrongBox when [preferStrongBox] and the device has one, and falls back to the
 * TEE when StrongBox is missing or refuses the key (some StrongBox implementations fail AES-256 generation). A key that
 * disappears (a Keystore reset) is not recreated behind existing entries: those fail to open with
 * [SecretStorageException] and the app offers "Reset identity"; new entries get a new key.
 */
class KeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val preferStrongBox: Boolean = true,
) : SecretCipher {
    private val lock = Any()
    private var cached: SecretKey? = null

    /** Where the key lives, for the diagnostics log; creates the key if needed. */
    val securityLevel: KeySecurityLevel
        get() =
            try {
                val key = key()
                val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
                    else -> KeySecurityLevel.UNKNOWN
                }
            } catch (e: GeneralSecurityException) {
                KeySecurityLevel.UNKNOWN
            } catch (e: ProviderException) {
                KeySecurityLevel.UNKNOWN
            }

    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): SealedSecret =
        guarded("seal") {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            SealedSecret(cipher.iv, ciphertext)
        }

    override fun open(
        sealed: SealedSecret,
        aad: ByteArray,
    ): ByteArray =
        guarded("open") {
            val key = key(create = false) ?: throw CryptoException("the Keystore key '$alias' is gone")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed.ciphertext)
        }

    private fun key(): SecretKey = key(create = true)!!

    private fun key(create: Boolean): SecretKey? =
        synchronized(lock) {
            cached?.let { return@synchronized it }
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = store.getKey(alias, null) as? SecretKey
            val key = existing ?: if (create) generate() else null
            cached = key
            key
        }

    private fun generate(): SecretKey {
        if (preferStrongBox) {
            try {
                return generate(strongBox = true)
            } catch (e: GeneralSecurityException) {
                // StrongBoxUnavailableException, or a StrongBox that refuses the parameters: use the TEE.
            } catch (e: ProviderException) {
                // Some StrongBox implementations fail generation with a ProviderException.
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private inline fun <T> guarded(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Keystore $what failed", e)
        } catch (e: ProviderException) {
            throw CryptoException("Keystore $what failed", e)
        } catch (e: java.io.IOException) {
            throw CryptoException("Keystore $what failed", e)
        }

    companion object {
        const val DEFAULT_ALIAS: String = "drop.secret-storage.aes256gcm.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}

/**
 * The device's secret stores, wired for production (F-B1, N11): the identity seed, `k_adv` and the database key all live
 * in one [AndroidSecretStorage] under `noBackupFilesDir/secrets`, sealed by a [KeystoreSecretCipher]. Nothing here is
 * backed up or moved to another device (the app's `data_extraction_rules.xml` excludes every domain), so the identity
 * survives app updates and is lost on uninstall, as F-B1 documents.
 */
object AndroidSecrets {
    /** Directory under `noBackupFilesDir` that holds the sealed secrets. */
    const val DIRECTORY: String = "secrets"

    /** The storage for [context]'s app. Call once per process and share the result. */
    fun storage(context: Context): AndroidSecretStorage {
        val app = context.applicationContext
        val strongBox = app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return AndroidSecretStorage(File(app.noBackupFilesDir, DIRECTORY), KeystoreSecretCipher(preferStrongBox = strongBox))
    }

    /** The identity store over [storage] (`core/crypto`), with [crypto] for the software Ed25519 key. */
    fun identityStore(
        storage: AndroidSecretStorage,
        crypto: AndroidCryptoProvider,
    ): SoftwareIdentityKeyStore = SoftwareIdentityKeyStore(storage, crypto)

    /** The `k_adv` store over [storage] (`core/crypto`, S3). */
    fun advertisingSecretStore(
        storage: AndroidSecretStorage,
        crypto: AndroidCryptoProvider,
    ): AdvertisingSecretStore = AdvertisingSecretStore(storage, crypto)
}
