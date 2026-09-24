package com.constrivo.drop.platform.android.wifi

import android.content.Context
import android.net.wifi.WifiManager
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.platform.android.capability.AndroidCapabilityDetector
import com.constrivo.drop.platform.android.lan.AndroidLanLinkProvider
import com.constrivo.drop.platform.android.lan.AndroidLanNetworks
import com.constrivo.drop.platform.android.lan.AndroidNsdApi
import com.constrivo.drop.platform.android.lan.NsdLanDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The phone's Wi-Fi rungs for the ladder and its LAN discovery (WP7c/d), built once per transfer-service node and
 * closed with it: the LAN link, the Wi-Fi Direct group (with the legacy-client join for phones without Wi-Fi Direct)
 * and the local-only hotspot, sharing one network-specifier joiner and one station monitor, plus the NSD browse
 * that puts desktops on the local network onto the radar (F-H4).
 *
 * Every part that cannot be created on this device (no Wi-Fi, no NSD service) is left out rather than failing the
 * service: transfers then stay on the rungs that exist, down to Bluetooth.
 */
class AndroidWifiStack(
    context: Context,
    detector: AndroidCapabilityDetector,
    clock: MonotonicClock,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext
    private val closeables = ArrayList<AutoCloseable>()

    private val listener =
        object : WifiLinkListener {
            override fun onJoinApprovalNeeded(ssid: String) = log("join approval needed for $ssid")

            override fun onRestore(report: RestoreReport) = log("network restore: $report")

            override fun onEvent(event: WifiLinkEvent) = log("wifi: $event")
        }

    /** The ladder's Wi-Fi rungs, LAN first (architecture §4). */
    val providers: List<WifiLinkProvider>

    private val nsd: NsdLanDiscovery?

    init {
        val permissions = WifiPermissionContext.of(app)
        val station = attempt("station monitor") { AndroidStationMonitor(app).also { it.start() }.also(closeables::add) }
        val joiner =
            attempt("network specifier") {
                SpecifierJoiner(
                    requester = AndroidNetworkRequester(app),
                    isForeground = AppForeground::isForegroundOrService,
                    station = station,
                    listener = listener,
                    clock = clock,
                )
            }
        val recorder =
            FiveGhzHostRecorder(
                store = PreferencesVerifiedHostStore(app),
                publish = detector::setVerifiedP2p5GhzHost,
                onError = { e -> log("5 GHz host record: ${e.message}") },
            ).also { it.restore() }

        val lanNetworks = attempt("LAN networks") { AndroidLanNetworks(app).also { it.start() }.also(closeables::add) }
        val lan = lanNetworks?.let { AndroidLanLinkProvider(it, permissions, clock, listener = listener) }
        val p2p =
            attempt("Wi-Fi Direct") {
                val radio = AndroidP2pRadio(app).also(closeables::add)
                AndroidP2pLinkProvider(radio, permissions, clock, legacyJoiner = joiner, hostRecorder = recorder, listener = listener)
            }
        val hotspot =
            attempt("local-only hotspot") {
                AndroidHotspotLinkProvider(
                    radio = AndroidHotspotRadio(app),
                    permissions = permissions,
                    clock = clock,
                    joiner = joiner,
                    station = station,
                    staApConcurrency = { detector.facts.value.staApConcurrency },
                    listener = listener,
                )
            }
        providers = listOfNotNull(lan, p2p, hotspot)

        nsd =
            attempt("NSD") {
                val multicast =
                    if (MulticastLockHolder.needsLockForNsd()) {
                        app.getSystemService(WifiManager::class.java)?.let { wifi ->
                            MulticastLockHolder(AndroidMulticastLock(wifi, "drop-nsd"), onError = { e ->
                                log("multicast lock: ${e.message}")
                            })
                        }
                    } else {
                        null
                    }
                NsdLanDiscovery(AndroidNsdApi(app), permissions, multicast, onError = {
                    message,
                    e,
                    ->
                    log("nsd: $message ${e?.message.orEmpty()}")
                })
            }
        log("wifi stack: ${providers.joinToString { it.kind.wireName }}; nsd=${nsd != null}")
    }

    /** Desktops and phones announcing on the local network, for the radar; empty without NSD. */
    fun lanEvents(): Flow<LanEvent> = nsd?.browse() ?: emptyFlow()

    override fun close() {
        for (closeable in closeables.asReversed()) runCatching { closeable.close() }
        closeables.clear()
    }

    private inline fun <T> attempt(
        what: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: RuntimeException) {
            log("no $what: ${e::class.simpleName}: ${e.message}")
            null
        }
}

/** [VerifiedHostStore] in the app's private preferences, so the verified 5 GHz host bit survives restarts (bit 4). */
class PreferencesVerifiedHostStore(
    context: Context,
) : VerifiedHostStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isVerified(): Boolean = prefs.getBoolean(KEY, false)

    override fun setVerified(verified: Boolean) {
        prefs.edit().putBoolean(KEY, verified).apply()
    }

    private companion object {
        const val PREFS = "drop.wifi"
        const val KEY = "verified_5ghz_host"
    }
}
