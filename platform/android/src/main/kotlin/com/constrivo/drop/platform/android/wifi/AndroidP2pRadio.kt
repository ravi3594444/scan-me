package com.constrivo.drop.platform.android.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [P2pRadio] over `WifiP2pManager` (architecture §8): one `Channel`, opened lazily and again after the framework
 * disconnected it (`ChannelListener.onChannelDisconnected`), and a receiver for `WIFI_P2P_STATE_CHANGED_ACTION` and
 * `WIFI_P2P_CONNECTION_CHANGED_ACTION` that feeds [state]. Callbacks arrive on [looper]'s thread (the main thread by
 * default; they only resume coroutines).
 *
 * The permission-guarded calls (`createGroup`, `connect`, `requestGroupInfo`) are made only after
 * [AndroidP2pLinkProvider] checked the permissions ([WifiPermissions]); a permission revoked meanwhile surfaces as the
 * `SecurityException` the provider maps, which is why lint's MissingPermission is suppressed here. The receiver is
 * registered at the first call (or by [start], when the radio session starts, so the first broadcasts arrive early);
 * [close] ends it when the session ends. Both are idempotent.
 */
@SuppressLint("MissingPermission")
class AndroidP2pRadio(
    context: Context,
    private val looper: Looper = Looper.getMainLooper(),
) : P2pRadio,
    AutoCloseable {
    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? = appContext.getSystemService(WifiP2pManager::class.java)
    private val lock = Any()
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private val mutable = MutableStateFlow(P2pRadioState())

    override val isAvailable: Boolean get() = manager != null

    override val state: StateFlow<P2pRadioState> = mutable.asStateFlow()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        mutable.update { it.copy(enabled = enabled, sequence = it.sequence + 1) }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        val group = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                        mutable.update {
                            it.copy(
                                connection = info?.let(::snapshotOf),
                                group = group?.let(::snapshotOf),
                                sequence =
                                    it.sequence + 1,
                            )
                        }
                    }
                }
            }
        }

    /** Registers the broadcast receiver; the first broadcasts (sticky on most builds) fill [state]. */
    fun start() {
        synchronized(lock) {
            if (registered || manager == null) return
            registered = true
            val filter =
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /** Unregisters the receiver and closes the channel; a group that is up stays up (the links tear themselves down). */
    override fun close() {
        synchronized(lock) {
            if (registered) {
                registered = false
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            channel?.let { runCatching { it.close() } }
            channel = null
        }
    }

    override fun createGroup(
        spec: P2pGroupSpec,
        done: (P2pActionResult) -> Unit,
    ) {
        val m = manager ?: return done(P2pActionResult.Failure(P2pFailureCodes.P2P_UNSUPPORTED))
        m.createGroup(channel(m), config(spec), listener(done))
    }

    override fun connect(
        spec: P2pGroupSpec,
        done: (P2pActionResult) -> Unit,
    ) {
        val m = manager ?: return done(P2pActionResult.Failure(P2pFailureCodes.P2P_UNSUPPORTED))
        m.connect(channel(m), config(spec), listener(done))
    }

    override fun cancelConnect(done: (P2pActionResult) -> Unit) {
        val m = manager ?: return done(P2pActionResult.Failure(P2pFailureCodes.P2P_UNSUPPORTED))
        m.cancelConnect(channel(m), listener(done))
    }

    override fun removeGroup(done: (P2pActionResult) -> Unit) {
        val m = manager ?: return done(P2pActionResult.Failure(P2pFailureCodes.P2P_UNSUPPORTED))
        m.removeGroup(channel(m), listener(done))
    }

    override fun requestGroupInfo(done: (P2pGroupSnapshot?) -> Unit) {
        val m = manager ?: return done(null)
        m.requestGroupInfo(channel(m)) { group -> done(group?.let(::snapshotOf)) }
    }

    override fun requestConnectionInfo(done: (P2pConnectionSnapshot?) -> Unit) {
        val m = manager ?: return done(null)
        m.requestConnectionInfo(channel(m)) { info -> done(info?.let(::snapshotOf)) }
    }

    override fun requestEnabled(done: (Boolean?) -> Unit) {
        val m = manager ?: return done(false)
        m.requestP2pState(channel(m)) { state -> done(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
    }

    private fun channel(m: WifiP2pManager): WifiP2pManager.Channel =
        synchronized(lock) {
            start()
            channel ?: m
                .initialize(appContext, looper) {
                    // The framework dropped the channel (Wi-Fi restarted, the service died): the next call opens a new
                    // one, and waiters re-check the group, which is probably gone.
                    synchronized(lock) { channel = null }
                    mutable.update {
                        it.copy(
                            connection = P2pConnectionSnapshot(false, false, null),
                            group = null,
                            sequence =
                                it.sequence + 1,
                        )
                    }
                }.also { channel = it }
        }

    private fun listener(done: (P2pActionResult) -> Unit): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = done(P2pActionResult.Success)

            override fun onFailure(reason: Int) = done(P2pActionResult.Failure(reason))
        }

    private companion object {
        /** `WifiP2pConfig.Builder` with the values of [spec] (the builder validates the name and passphrase again). */
        fun config(spec: P2pGroupSpec): WifiP2pConfig =
            WifiP2pConfig
                .Builder()
                .setNetworkName(spec.networkName)
                .setPassphrase(spec.passphrase)
                .setGroupOperatingBand(spec.band.platformValue)
                .enablePersistentMode(spec.persistent)
                .build()

        fun snapshotOf(info: WifiP2pInfo): P2pConnectionSnapshot =
            P2pConnectionSnapshot(info.groupFormed, info.isGroupOwner, info.groupOwnerAddress)

        fun snapshotOf(group: WifiP2pGroup): P2pGroupSnapshot =
            P2pGroupSnapshot(
                networkName = group.networkName,
                passphrase = if (group.isGroupOwner) group.passphrase else null,
                frequencyMhz = WifiFrequencies.valid(group.frequency),
                interfaceName = group.getInterface(),
                isGroupOwner = group.isGroupOwner,
                clientCount = group.clientList?.size ?: 0,
            )
    }
}
