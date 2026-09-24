package com.constrivo.drop.platform.android.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import com.constrivo.drop.core.crypto.hexToBytes
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.crypto.trust.AdvertisingSecretStore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * On a device (lab, WP7b): the Keystore-backed secret storage and the provider's runtime probe. Records which backend
 * each primitive uses and where the wrapping key lives, for the per-device table the lab keeps (decision 7).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keystoreCipherSealsAndBindsTheAssociatedData() {
        val cipher = KeystoreSecretCipher(alias = "drop.test.${System.nanoTime()}")
        val sealed = cipher.seal("secret".encodeToByteArray(), "aad".encodeToByteArray())
        assertEquals(12, sealed.iv.size)
        assertContentEquals("secret".encodeToByteArray(), cipher.open(sealed, "aad".encodeToByteArray()))
        assertFailsWith<Exception> { cipher.open(sealed, "other".encodeToByteArray()) }
        assertNotEquals(KeySecurityLevel.UNKNOWN, cipher.securityLevel)
        println("drop-lab: Keystore key security level ${cipher.securityLevel}")
    }

    @Test
    fun theProductionStorageKeepsTheIdentityAcrossInstances() {
        val dir = File(context.noBackupFilesDir, "secrets-test-${System.nanoTime()}")
        try {
            val crypto = AndroidCryptoProvider()
            val storage = AndroidSecretStorage(dir, KeystoreSecretCipher(alias = "drop.test.identity"))
            val identity = SoftwareIdentityKeyStore(storage, crypto).loadOrCreate()
            val kAdv = AdvertisingSecretStore(storage, crypto).current()
            val reopened = AndroidSecretStorage(dir, KeystoreSecretCipher(alias = "drop.test.identity"))
            assertContentEquals(identity.publicKey, SoftwareIdentityKeyStore(reopened, crypto).loadOrCreate().publicKey)
            assertEquals(kAdv, AdvertisingSecretStore(reopened, crypto).current())
            assertTrue(AndroidSecrets.storage(context).let { it.get("drop.test.absent") == null })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theProviderPassesTheRfcVectorsOnThisDevice() {
        val crypto = AndroidCryptoProvider()
        assertEquals(Rfc7748.SHARED, crypto.x25519(Rfc7748.ALICE_PRIVATE.hexToBytes(), Rfc7748.BOB_PUBLIC.hexToBytes()).toHex())
        for (v in Rfc8032.TESTS) assertEquals(v.signature, crypto.ed25519Sign(v.seed.hexToBytes(), v.message.hexToBytes()).toHex())
        println(
            "drop-lab: X25519 ${crypto.x25519Backend}, Ed25519 ${crypto.ed25519Backend}, " +
                "ChaCha20-Poly1305 ${crypto.chaChaBackend}, AES hardware ${crypto.hasAesHardware}",
        )
    }
}
