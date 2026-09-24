package com.constrivo.drop.platform.android.lan

import android.content.Context
import android.net.Network
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresExtension
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [NsdApi] over `NsdManager` (architecture §8 "LAN discovery"): `registerService`, `discoverServices`, and per found
 * service `registerServiceInfoCallback` where available or `resolveService` before. `NsdManager` ships in the Tiramisu
 * SDK extensions, so its newer calls are gated by the extension version, not the API level (as `NsdManager`'s own
 * documentation asks): the service-info callback and `getHostAddresses` from extension 7, per-network scoping from 3,
 * `DiscoveryRequest` from 12 and its flags from 22. For an app targeting API 37 on Android 17 discovery runs with
 * `DiscoveryRequest.FLAG_NO_PICKER`, so it never shows the system's service picker (the caller checks
 * `ACCESS_LOCAL_NETWORK` first).
 *
 * [network] scopes announcing and browsing to one network, for example the Wi-Fi station, so the record is not
 * announced on a Wi-Fi Direct group or hotspot this phone hosts; null (the default) uses every network. Callbacks run on
 * the binder thread that delivers them ([executor]); [NsdLanDiscovery] only queues them.
 */
class AndroidNsdApi(
    context: Context,
    private val network: () -> Network? = { null },
    private val executor: Executor = Executor { it.run() },
) : NsdApi {
    private val nsd: NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java) ?: throw IllegalStateException("no NSD service")
    private val targetSdk = context.applicationContext.applicationInfo.targetSdkVersion

    @get:ChecksSdkIntAtLeast(api = WATCH_EXTENSION, extension = Build.VERSION_CODES.TIRAMISU)
    override val supportsServiceWatch: Boolean get() = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= WATCH_EXTENSION

    override fun register(
        registration: NsdRegistration,
        callback: NsdRegistrationCallback,
    ): NsdHandle {
        val info =
            NsdServiceInfo().apply {
                serviceName = registration.serviceName
                serviceType = registration.serviceType
                port = registration.port
                registration.attributes.forEach { (key, value) -> setAttribute(key, value) }
            }
        val scope = network()
        if (scope != null && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= NETWORK_EXTENSION) scopeTo(info, scope)
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = callback.onRegistered(serviceInfo.serviceName ?: "")

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = callback.onRegistrationFailed(errorCode)

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = callback.onUnregistered()

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = callback.onUnregistrationFailed(errorCode)
            }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return once { nsd.unregisterService(listener) }
    }

    override fun discover(
        serviceType: String,
        callback: NsdDiscoveryCallback,
    ): NsdHandle {
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = callback.onStarted()

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = callback.onStartFailed(errorCode)

                override fun onServiceFound(serviceInfo: NsdServiceInfo) =
                    callback.onFound(NsdFoundService(serviceInfo.serviceName ?: "", serviceInfo.serviceType, serviceInfo))

                override fun onServiceLost(serviceInfo: NsdServiceInfo) =
                    callback.onLost(NsdFoundService(serviceInfo.serviceName ?: "", serviceInfo.serviceType, serviceInfo))

                override fun onDiscoveryStopped(serviceType: String) = callback.onStopped()

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit
            }
        val scope = network()
        val pickerRules = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && targetSdk >= Build.VERSION_CODES.CINNAMON_BUN
        when {
            pickerRules && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= FLAGS_EXTENSION -> {
                discoverWithoutPicker(serviceType, scope, listener)
            }

            scope != null && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= NETWORK_EXTENSION -> {
                discoverOn(serviceType, scope, listener)
            }

            else -> {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
        return once { nsd.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION") // resolveService: the resolve of builds without the service-info callback.
    override fun resolve(
        service: NsdFoundService,
        callback: NsdResolveCallback,
    ): NsdHandle {
        val info = service.token as? NsdServiceInfo ?: throw IllegalArgumentException("not a platform service: $service")
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = callback.onResolved(resolvedOf(serviceInfo))

                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = callback.onResolveFailed(errorCode)
            },
        )
        // Without the service-info callback a resolve cannot be stopped; it ends with its callback.
        return NsdHandle {}
    }

    override fun watch(
        service: NsdFoundService,
        callback: NsdWatchCallback,
    ): NsdHandle {
        val info = service.token as? NsdServiceInfo ?: throw IllegalArgumentException("not a platform service: $service")
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= WATCH_EXTENSION) return watchWith(info, callback)
        throw IllegalStateException("registerServiceInfoCallback needs Tiramisu SDK extension $WATCH_EXTENSION")
    }

    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = WATCH_EXTENSION)
    private fun watchWith(
        info: NsdServiceInfo,
        callback: NsdWatchCallback,
    ): NsdHandle {
        val serviceCallback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = callback.onFailed(errorCode)

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = callback.onUpdated(resolvedOf(serviceInfo))

                override fun onServiceLost() = callback.onLost()

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
        nsd.registerServiceInfoCallback(info, executor, serviceCallback)
        return once { nsd.unregisterServiceInfoCallback(serviceCallback) }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = NETWORK_EXTENSION)
    private fun scopeTo(
        info: NsdServiceInfo,
        scope: Network,
    ) {
        info.network = scope
    }

    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = NETWORK_EXTENSION)
    private fun discoverOn(
        serviceType: String,
        scope: Network,
        listener: NsdManager.DiscoveryListener,
    ) = nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, scope, executor, listener)

    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = FLAGS_EXTENSION)
    private fun discoverWithoutPicker(
        serviceType: String,
        scope: Network?,
        listener: NsdManager.DiscoveryListener,
    ) {
        val request =
            DiscoveryRequest
                .Builder(serviceType)
                .setFlags(DiscoveryRequest.FLAG_NO_PICKER)
                .apply { scope?.let { setNetwork(it) } }
                .build()
        nsd.discoverServices(request, executor, listener)
    }

    private fun resolvedOf(info: NsdServiceInfo): NsdResolvedService {
        val hosts =
            if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= WATCH_EXTENSION) {
                addressesOf(info)
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(info.host)
            }
        return NsdResolvedService(info.serviceName ?: "", info.port, hosts, info.attributes.orEmpty())
    }

    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = WATCH_EXTENSION)
    private fun addressesOf(info: NsdServiceInfo) = info.hostAddresses.toList()

    private companion object {
        /** `registerServiceInfoCallback` and `getHostAddresses`. */
        const val WATCH_EXTENSION = 7

        /** `NsdServiceInfo.setNetwork` and the per-network `discoverServices`. */
        const val NETWORK_EXTENSION = 3

        /** `DiscoveryRequest.Builder.setFlags` and `FLAG_NO_PICKER` (the whole `DiscoveryRequest` needs 12). */
        const val FLAGS_EXTENSION = 22

        /** A handle that runs [stop] once and never throws (a listener the platform already dropped throws). */
        fun once(stop: () -> Unit): NsdHandle {
            val done = AtomicBoolean(false)
            return NsdHandle {
                if (done.compareAndSet(false, true)) {
                    try {
                        stop()
                    } catch (_: RuntimeException) {
                        // Not registered any more (it failed, or the service restarted).
                    }
                }
            }
        }
    }
}
