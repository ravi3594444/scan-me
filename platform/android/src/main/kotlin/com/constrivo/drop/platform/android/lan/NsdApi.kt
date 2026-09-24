package com.constrivo.drop.platform.android.lan

import java.io.IOException
import java.net.InetAddress

/** `NsdManager` failure codes as a type (`FAILURE_*`), plus this package's own [TIMEOUT]. */
enum class NsdError(
    val platformCode: Int,
) {
    INTERNAL_ERROR(0),
    ALREADY_ACTIVE(3),
    MAX_LIMIT(4),
    OPERATION_NOT_RUNNING(5),
    BAD_PARAMETERS(6),
    PERMISSION_DENIED(7),

    /** The platform did not answer in time. */
    TIMEOUT(-1),

    /** A code this build does not know. */
    UNKNOWN(-2),
    ;

    companion object {
        fun fromPlatform(code: Int): NsdError = entries.firstOrNull { it.platformCode == code && it.platformCode >= 0 } ?: UNKNOWN
    }
}

/**
 * Announcing or browsing on the LAN failed (architecture §5.4). An [IOException], as the desktop `LanDiscovery`
 * reports its failures.
 *
 * @property missingPermissions for [NsdError.PERMISSION_DENIED] found before any call: the permissions to request
 *   (`ACCESS_LOCAL_NETWORK` on Android 17 for apps targeting it).
 */
class LanDiscoveryException(
    val error: NsdError,
    message: String,
    val missingPermissions: List<String> = emptyList(),
    cause: Throwable? = null,
) : IOException(message, cause)

/** A service to register (`NsdServiceInfo` for `registerService`); [attributes] passed [NsdTxtMapping.toAttributes]. */
data class NsdRegistration(
    val serviceName: String,
    val serviceType: String,
    val port: Int,
    val attributes: Map<String, String>,
)

/**
 * A service a browse found or lost (`onServiceFound` / `onServiceLost`). [token] is the platform's `NsdServiceInfo`,
 * which resolving and watching need; tests leave it null.
 */
class NsdFoundService(
    val serviceName: String,
    val serviceType: String?,
    val token: Any? = null,
) {
    override fun toString(): String = "NsdFoundService($serviceName, $serviceType)"
}

/** A resolved service: its addresses, port and raw TXT attributes. */
class NsdResolvedService(
    val serviceName: String,
    val port: Int,
    val hosts: List<InetAddress>,
    val attributes: Map<String, ByteArray?>,
) {
    override fun toString(): String = "NsdResolvedService($serviceName, ${hosts.map { it.hostAddress }}, $port, ${attributes.keys})"
}

/** `NsdManager.RegistrationListener`. */
interface NsdRegistrationCallback {
    /** Registered under [serviceName], which the platform may have renamed to resolve a conflict. */
    fun onRegistered(serviceName: String)

    fun onRegistrationFailed(errorCode: Int)

    fun onUnregistered()

    fun onUnregistrationFailed(errorCode: Int)
}

/** `NsdManager.DiscoveryListener`. */
interface NsdDiscoveryCallback {
    fun onStarted()

    fun onStartFailed(errorCode: Int)

    fun onFound(service: NsdFoundService)

    fun onLost(service: NsdFoundService)

    fun onStopped()
}

/** `NsdManager.ResolveListener` (resolving before API 34). */
interface NsdResolveCallback {
    fun onResolved(service: NsdResolvedService)

    fun onResolveFailed(errorCode: Int)
}

/** `NsdManager.ServiceInfoCallback` (API 34+): the service's current addresses and TXT, until it is lost. */
interface NsdWatchCallback {
    fun onUpdated(service: NsdResolvedService)

    fun onLost()

    fun onFailed(errorCode: Int)
}

/** Stops what a call started (`unregisterService`, `stopServiceDiscovery`, `unregisterServiceInfoCallback`). */
fun interface NsdHandle {
    /** Idempotent; never throws. */
    fun close()
}

/**
 * The `NsdManager` calls of [NsdLanDiscovery], a seam so its logic runs in JVM tests; [AndroidNsdApi] is the platform
 * implementation. Callbacks come from any thread. Every call may throw `IllegalArgumentException` at once for a bad
 * argument or a listener already in use.
 */
interface NsdApi {
    /** `registerServiceInfoCallback` exists (API 34+): found services are watched, not resolved once. */
    val supportsServiceWatch: Boolean

    fun register(
        registration: NsdRegistration,
        callback: NsdRegistrationCallback,
    ): NsdHandle

    fun discover(
        serviceType: String,
        callback: NsdDiscoveryCallback,
    ): NsdHandle

    /** Resolves once (`resolveService`); only called when [supportsServiceWatch] is false. */
    fun resolve(
        service: NsdFoundService,
        callback: NsdResolveCallback,
    ): NsdHandle

    /** Watches (`registerServiceInfoCallback`); only called when [supportsServiceWatch] is true. */
    fun watch(
        service: NsdFoundService,
        callback: NsdWatchCallback,
    ): NsdHandle
}
