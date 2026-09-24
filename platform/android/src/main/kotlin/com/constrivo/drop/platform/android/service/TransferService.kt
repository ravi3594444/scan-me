package com.constrivo.drop.platform.android.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.AndroidClocks
import com.constrivo.drop.platform.android.ble.AndroidBeaconRadio
import com.constrivo.drop.platform.android.ble.BluetoothPowerMonitor
import com.constrivo.drop.platform.android.bluetooth.BluetoothChannelConnector
import com.constrivo.drop.platform.android.bluetooth.BluetoothChannelListener
import com.constrivo.drop.platform.android.capability.AndroidCapabilityDetector
import com.constrivo.drop.platform.android.crypto.AndroidCryptoProvider
import com.constrivo.drop.platform.android.crypto.AndroidSecrets
import com.constrivo.drop.platform.android.data.AndroidDatabase
import com.constrivo.drop.platform.android.notification.NotificationModel
import com.constrivo.drop.platform.android.notification.ProgressAnnouncer
import com.constrivo.drop.platform.android.notification.ProgressThrottle
import com.constrivo.drop.platform.android.notification.TransferNotifications
import com.constrivo.drop.platform.android.permission.RadioPermissionState
import com.constrivo.drop.platform.android.permission.RadioPermissions
import com.constrivo.drop.platform.android.power.AndroidPowerPolicy
import com.constrivo.drop.platform.android.wifi.AndroidWifiStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The transfer service (WP7e; architecture §10.1 process model, §11; spec change S9; F-E12, F-D2, F-F6): one service
 * per process that owns the radios, the node (engine, ladder, database, trust, storage) and the notifications. The UI
 * binds to it ([LocalBinder]) and observes the node's `StateFlow`s.
 *
 * **Lifecycle** ([ServiceLifecycle]). Foreground type `connectedDevice` for the radio session (the phone visible to
 * nearby devices in the background, a transfer waiting for its peer, an offer on the card), plus `dataSync` while a
 * transfer runs; not in the foreground otherwise, and stopped 60 s after the last transfer unless visibility still
 * needs the radio session. A bound UI keeps the service alive past that; it is destroyed when the UI unbinds. The
 * service starts itself (a plain start, while the app is in the foreground) before it goes into the foreground, so it
 * outlives its clients, and so do the read grants of a share handed to it ([holdGrants]). Android 15's `dataSync`
 * limit (S9) is counted and logged ([DataSyncQuota]); when the system reports it spent (`onTimeout`) the service leaves
 * the foreground and stops within seconds, as Android requires, and runs later transfers under `connectedDevice`
 * alone until the user brings the app back.
 *
 * **Radios.** WP7a's [AndroidBeaconRadio] and [AndroidCapabilityDetector], WP7b's [BluetoothChannelListener] and
 * [BluetoothChannelConnector], sharing one [BluetoothPowerMonitor]. The beacon follows the settings' visibility and
 * nickname and the detected facts; the permissions are re-read whenever the UI comes back ([LocalBinder.refreshPermissions]),
 * which also reopens the Bluetooth servers once `BLUETOOTH_CONNECT` is granted; the detector learns the save volume
 * (capability bit 10). The epoch boundary and the end of "Everyone for 10 min" wake the node from an inexact
 * `setAndAllowWhileIdle` alarm (no exact-alarm permission) while the radio session runs.
 *
 * **Power** ([AndroidPowerPolicy]): the thermal listener lowers the stream count (F-F6) and a partial wake lock is held
 * while any transfer or the browser page runs.
 *
 * **Notifications** ([TransferNotifications]): the ongoing progress at most every 500 ms with an accessibility
 * announcement every 25 %, the incoming offer as a heads-up with Accept and Decline while the app is in the background
 * (never a full-screen intent), and completions with "Open".
 *
 * The `Application` must implement [TransferServiceHost]. Not exported; notification actions reach it through
 * `PendingIntent`s.
 */
class TransferService : Service() {
    /** The UI's handle on the service (same process; the service is not exported). */
    inner class LocalBinder : Binder() {
        /** The running node, or null while it starts (and after [startFailure]). */
        val node: StateFlow<AndroidNode?> get() = nodeState.asStateFlow()

        /** Why the node could not start (a database written by a newer app, an identity that does not open), or null. */
        val startFailure: StateFlow<String?> get() = failureState.asStateFlow()

        /** The ring-buffer log, for Settings → About's export. */
        val log: ServiceLog get() = host.serviceLog

        /** The radar screen is shown (F-A2): discovery scans in the foreground mode. */
        fun setRadarVisible(visible: Boolean) = this@TransferService.setRadarVisible(visible)

        /**
         * An activity of the app is started: incoming offers go to the in-app card instead of the heads-up, and Android's
         * `dataSync` count is reset (S9).
         */
        fun setUiVisible(visible: Boolean) = this@TransferService.setUiVisible(visible)

        /** Re-reads the radio permissions (the app returned, or the user answered a request; §11). */
        fun refreshPermissions() = this@TransferService.refreshPermissions()
    }

    /** What one started node holds; released after the node stopped. */
    private class Running(
        val node: AndroidNode,
        val power: BluetoothPowerMonitor,
        val beacon: AndroidBeaconRadio,
        val detector: AndroidCapabilityDetector,
        val connector: BluetoothChannelConnector,
        val powerPolicy: AndroidPowerPolicy,
        val stores: AndroidNodeStores,
        val background: CoroutineScope,
        val wifi: AndroidWifiStack,
    ) {
        fun release() {
            runCatching { wifi.close() }
            runCatching { detector.stop() }
            runCatching { powerPolicy.stop() }
            runCatching { connector.close() }
            runCatching { beacon.close() }
            background.cancel()
        }
    }

    private lateinit var host: TransferServiceHost
    private lateinit var log: ServiceLog
    private lateinit var quota: DataSyncQuota
    private lateinit var notifications: TransferNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private val nodeState = MutableStateFlow<AndroidNode?>(null)
    private val failureState = MutableStateFlow<String?>(null)
    private val permissions = MutableStateFlow(RadioPermissionState(0, emptySet()))
    private val radarVisible = MutableStateFlow(false)
    private val uiVisible = MutableStateFlow(false)
    private val foregroundState = MutableStateFlow<Set<ForegroundKind>>(emptySet())
    private val lifecycle = ServiceLifecycle()
    private val throttle = ProgressThrottle()
    private val announcer = ProgressAnnouncer()
    private val shownOffers = HashSet<String>()
    private val offerTimers = HashMap<String, Job>()
    private var running: Running? = null
    private var started = false
    private var lastStartId = 0
    private var stopJob: Job? = null
    private var flushJob: Job? = null
    private var lastOngoing: Notification? = null
    private var lastStageKey: List<Any>? = null

    private val foreground: Set<ForegroundKind> get() = foregroundState.value

    override fun onCreate() {
        super.onCreate()
        host = application as? TransferServiceHost ?: throw IllegalStateException("the Application must implement TransferServiceHost")
        log = host.serviceLog
        quota = DataSyncQuota(log = log.tagged("S9"))
        notifications =
            TransferNotifications(
                context = this,
                texts = host.notificationTexts,
                smallIcon = host.notificationIcon,
                launchIntent = host::launchIntent,
                serviceIntent = { action, id -> serviceIntent(this, action, id) },
                cancelAction = ACTION_CANCEL,
                acceptAction = ACTION_ACCEPT,
                declineAction = ACTION_DECLINE,
            )
        notifications.createChannels()
        permissions.value = RadioPermissions.read(this)
        log.log(TAG, "created")
        scope.launch { startNode() }
        scope.launch { host.onboarded.drop(1).collect { evaluate() } }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        started = true
        lastStartId = startId
        val id = intent?.getStringExtra(EXTRA_ID)
        val node = nodeState.value
        when (intent?.action) {
            ACTION_ACCEPT -> {
                id?.let {
                    node?.accept(it, alwaysAccept = false)
                    notifications.cancelOffer(it)
                }
            }

            ACTION_DECLINE -> {
                id?.let {
                    node?.decline(it)
                    notifications.cancelOffer(it)
                }
            }

            ACTION_CANCEL -> {
                id?.let { node?.cancel(it) }
            }

            ACTION_WAKE -> {
                node?.recheckVisibility()
            }

            ACTION_HOLD_GRANTS -> {
                log.log(TAG, "holding read grants of ${intent.clipData?.itemCount ?: 0} files")
            }
        }
        evaluate()
        return if (lifecycle.phase == ServicePhase.IDLE) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onRebind(intent: Intent) = Unit

    override fun onUnbind(intent: Intent): Boolean {
        // Every client is gone: no activity shows the radar or the cards any more.
        setRadarVisible(false)
        setUiVisible(false)
        return true
    }

    /**
     * Android 15's time limit for `dataSync` (S9): the service is no longer in the foreground and must stop within
     * seconds. Running transfers go on while the process lives, and resume after it if it does not (S8, T-07).
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return
        quota.timedOut(now())
        foregroundState.value = emptySet()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        nodeState.value?.discovery?.setForegroundService(false)
        started = false
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A new language renames the channels and the texts.
        notifications.createChannels()
        lastStageKey = null
        throttle.reset()
        updateOngoing()
    }

    override fun onDestroy() {
        log.log(TAG, "destroyed")
        val held = running
        running = null
        nodeState.value = null
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        notifications.cancelOngoing()
        shownOffers.forEach { notifications.cancelOffer(it) }
        cancelAlarms()
        held?.let(::shutDown)
        super.onDestroy()
    }

    /**
     * Stops [held]'s node and releases its radios off the main thread (the node takes a few seconds); the next instance
     * waits for it before it opens the radios and the database again.
     */
    private fun shutDown(held: Running) {
        previousShutdown.set(
            shutdownScope.launch {
                try {
                    held.node.stop()
                } finally {
                    held.release()
                }
            },
        )
    }

    // =============================================================================================================
    // Node
    // =============================================================================================================

    private suspend fun startNode() {
        previousShutdown.get()?.join()
        val shared = Shared.of(this)
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val power = BluetoothPowerMonitor(this)
        val beacon = AndroidBeaconRadio(this, power = power)
        val detector = AndroidCapabilityDetector(this, shared.crypto, power)
        val listener = BluetoothChannelListener(this, power = power)
        val connector = BluetoothChannelConnector(this, deviceLookup = beacon::deviceFor)
        val powerPolicy = AndroidPowerPolicy(this, background, onEvent = log.tagged("power"))
        val stores = AndroidNodeStores(this)
        // WP7c/d: the phone's own Wi-Fi Direct, hotspot and LAN rungs and its NSD browse; a host may still supply
        // its own (tests), which then replace the built-in ones.
        val wifi = AndroidWifiStack(this, detector, AndroidClocks.elapsedRealtime, log.tagged("wifi"))
        val providers =
            try {
                host.wifiProviders(this).ifEmpty { wifi.providers }
            } catch (e: RuntimeException) {
                log.log(TAG, "no Wi-Fi providers from the host: ${e.message}")
                wifi.providers
            }
        val browserHost = WifiBrowserHost(this, providers, shared.crypto, log = log.tagged("browser"))
        val node =
            AndroidNode(
                AndroidNodeConfig(
                    openDatabase = { AndroidDatabase.open(applicationContext, shared.secrets, shared.crypto, AndroidClocks.wall) },
                    secrets = shared.secrets,
                    crypto = shared.crypto,
                    beaconRadio = beacon,
                    bluetooth = AndroidBluetoothPaths(listener, connector),
                    stores = stores,
                    power = powerPolicy,
                    defaultNickname = host.defaultNickname,
                    radioFacts = detector.facts,
                    radioPermissions = permissions,
                    wallClock = AndroidClocks.wall,
                    monotonicClock = AndroidClocks.elapsedRealtime,
                    wifiProviders = providers,
                    lanDialer = host.lanDialer(this),
                    lanEvents = merge(host.lanEvents(this), wifi.lanEvents()),
                    browserHost = browserHost.takeIf { it.available },
                    log = log.tagged("node"),
                ),
            )
        val held = Running(node, power, beacon, detector, connector, powerPolicy, stores, background, wifi)
        detector.start()
        powerPolicy.start()
        try {
            node.start()
        } catch (e: CancellationException) {
            // Destroyed while starting.
            shutDown(held)
            throw e
        } catch (e: Exception) {
            log.log(TAG, "the node did not start: ${e::class.simpleName}: ${e.message}")
            failureState.value = e.message ?: e::class.simpleName.orEmpty()
            shutDown(held)
            evaluate()
            return
        }
        running = held
        node.discovery.setRadarVisible(radarVisible.value)
        node.discovery.setForegroundService(foreground.isNotEmpty())
        nodeState.value = node
        observe(node, held)
        evaluate()
    }

    private fun observe(
        node: AndroidNode,
        held: Running,
    ) {
        scope.launch {
            merge(node.activity.map { }, node.effectiveVisibility.map { }).collect { evaluate() }
        }
        scope.launch {
            combine(node.transfers, node.browserShare, node.effectiveVisibility, foregroundState) { _, _, _, _ ->
            }.collect { updateOngoing() }
        }
        scope.launch {
            combine(node.offers, uiVisible) { offers, visible -> offers to visible }.collect { (offers, visible) ->
                syncOffers(node, offers, visible)
            }
        }
        scope.launch {
            node.events.collect { event ->
                when (event) {
                    is NodeEvent.TransferFinished -> onFinished(event)
                    is NodeEvent.Paired -> log.log(TAG, "paired with ${event.deviceId}")
                    is NodeEvent.Problem -> Unit // the node logged it
                }
            }
        }
        scope.launch {
            combine(node.discovery.advertisement, node.visibility, foregroundState) { _, _, _ -> }.collect { rearmAlarms(node) }
        }
        scope.launch {
            node.settings
                .map { it?.saveLocation }
                .distinctUntilChanged()
                .collect { location -> held.detector.setSaveVolume(held.stores.saveVolumeName(location)) }
        }
        scope.launch {
            node.discovery.lastError.collect { error -> if (error != null) log.log("discovery", error) }
        }
    }

    // =============================================================================================================
    // Lifecycle (S9)
    // =============================================================================================================

    private fun evaluate() {
        val node = nodeState.value
        val decision =
            lifecycle.update(
                ServiceInputs(
                    activity = node?.activity?.value ?: NodeActivity(),
                    onboarded = host.onboarded.value,
                    visibility = node?.effectiveVisibility?.value ?: Visibility.HIDDEN,
                    dataSyncAllowed = !quota.exhausted,
                    nowElapsedMillis = now(),
                ),
            )
        applyForeground(decision.foreground)
        scheduleStop(decision.stopAtElapsedMillis)
    }

    private fun applyForeground(target: Set<ForegroundKind>) {
        if (target == foreground) return
        if (target.isEmpty()) {
            leaveForeground()
            return
        }
        if (!started) {
            // Only bound: start first, so the service outlives the UI; onStartCommand decides again.
            try {
                startService(Intent(this, TransferService::class.java).setAction(ACTION_PROMOTE))
            } catch (e: IllegalStateException) {
                log.log(TAG, "could not start in the background: ${e.message}")
            }
            return
        }
        enterForeground(target)
    }

    private fun enterForeground(target: Set<ForegroundKind>) {
        val notification = lastOngoing ?: buildOngoing()
        try {
            startForeground(TransferNotifications.ONGOING_ID, notification, serviceTypes(target))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (background start, spent dataSync limit), SecurityException.
            if (ForegroundKind.DATA_SYNC in target && ForegroundKind.DATA_SYNC !in foreground) {
                quota.refused(now(), e.message)
                val fallback = target - ForegroundKind.DATA_SYNC
                if (fallback != foreground) enterForeground(fallback)
                return
            }
            log.log(TAG, "could not enter the foreground as $target: ${e::class.simpleName}: ${e.message}")
            return
        }
        val before = foreground
        if (ForegroundKind.DATA_SYNC in target && ForegroundKind.DATA_SYNC !in before) quota.started(now())
        if (ForegroundKind.DATA_SYNC !in target && ForegroundKind.DATA_SYNC in before) quota.stopped(now())
        if (before.isEmpty()) log.log(TAG, "foreground as ${target.joinToString { it.name.lowercase() }}")
        foregroundState.value = target
        lastOngoing = notification
        nodeState.value?.discovery?.setForegroundService(true)
    }

    private fun leaveForeground() {
        if (foreground.isEmpty()) return
        if (ForegroundKind.DATA_SYNC in foreground) quota.stopped(now())
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundState.value = emptySet()
        lastOngoing = null
        lastStageKey = null
        throttle.reset()
        nodeState.value?.discovery?.setForegroundService(false)
        log.log(TAG, "left the foreground")
    }

    private fun scheduleStop(atElapsedMillis: Long?) {
        stopJob?.cancel()
        stopJob = null
        if (atElapsedMillis == null || !started) return
        stopJob =
            scope.launch {
                delay((atElapsedMillis - now()).coerceAtLeast(0))
                if (lifecycle.phase != ServicePhase.IDLE) return@launch
                log.log(TAG, "stopping: nothing needed the service for ${lifecycle.lingerMillis / 1000} s")
                leaveForeground()
                started = false
                // A start that arrived meanwhile (a new share) keeps the service running.
                stopSelfResult(lastStartId)
            }
    }

    private fun setRadarVisible(visible: Boolean) {
        radarVisible.value = visible
        nodeState.value?.discovery?.setRadarVisible(visible)
    }

    private fun setUiVisible(visible: Boolean) {
        if (uiVisible.value == visible) return
        uiVisible.value = visible
        if (visible) {
            quota.userReturned(now())
            refreshPermissions()
            evaluate()
        }
    }

    private fun refreshPermissions() {
        val before = permissions.value
        val current = RadioPermissions.read(this)
        permissions.value = current
        val held = running ?: return
        held.power.refresh()
        held.detector.refresh()
        // BLUETOOTH_CONNECT just granted: open the GATT server and the L2CAP listener now, not at their next retry.
        if (current.canConnect && !before.canConnect) held.node.config.bluetooth.refresh()
    }

    // =============================================================================================================
    // Notifications
    // =============================================================================================================

    private fun buildOngoing(): Notification {
        val node = nodeState.value
        val content =
            NotificationModel.ongoing(
                node?.transfers?.value.orEmpty(),
                node?.browserShare?.value ?: BrowserShareStatus.Idle,
                node?.effectiveVisibility?.value ?: Visibility.HIDDEN,
                host.notificationTexts,
            )
        return notifications.ongoing(content, null)
    }

    private fun updateOngoing() {
        val node = nodeState.value ?: return
        if (foreground.isEmpty()) return
        val transfers = node.transfers.value
        val content = NotificationModel.ongoing(transfers, node.browserShare.value, node.effectiveVisibility.value, host.notificationTexts)
        val wait = throttle.admit(content.stageKey, now())
        if (wait > 0) {
            if (flushJob?.isActive != true) {
                flushJob =
                    scope.launch {
                        delay(wait)
                        updateOngoing()
                    }
            }
            return
        }
        flushJob?.cancel()
        announcer.retain(transfers.map { it.id })
        val stageChanged = content.stageKey != lastStageKey
        lastStageKey = content.stageKey
        val milestone = content.cancelTransferId?.let { announcer.milestone(it, content.percent) }
        val announcement =
            when {
                milestone != null -> host.notificationTexts.announce(content.title, milestone)
                stageChanged -> "${content.title}. ${content.text}"
                else -> null
            }
        val notification = notifications.ongoing(content, announcement)
        lastOngoing = notification
        notifications.postOngoing(notification)
        quota.check(now())
    }

    private fun syncOffers(
        node: AndroidNode,
        offers: List<NodeOffer>,
        visible: Boolean,
    ) {
        val ids = offers.mapTo(HashSet()) { it.id }
        for (gone in shownOffers - ids) notifications.cancelOffer(gone)
        shownOffers.retainAll(ids)
        for ((id, job) in offerTimers.entries.toList()) {
            if (id !in ids) {
                job.cancel()
                offerTimers.remove(id)
            }
        }
        for (offer in offers) {
            if (offer.id !in offerTimers) {
                // Backstop for the card's 30 s (design §5.1) while no card counts down; the in-app one answers first.
                offerTimers[offer.id] =
                    scope.launch {
                        delay((offer.arrivedAtElapsedMillis + offer.timeoutMillis + OFFER_GRACE_MILLIS - now()).coerceAtLeast(0))
                        node.offerTimedOut(offer.id)
                    }
            }
        }
        if (visible) {
            // The in-app card shows them.
            for (id in shownOffers) notifications.cancelOffer(id)
            shownOffers.clear()
            return
        }
        for (offer in offers) {
            if (offer.id in shownOffers) continue
            notifications.showOffer(NotificationModel.offer(offer, host.notificationTexts), now())
            shownOffers += offer.id
        }
    }

    private fun onFinished(event: NodeEvent.TransferFinished) {
        notifications.cancelOffer(event.transfer.id)
        // While the app is in front, the radar's tray and History show the end (F-D3).
        if (uiVisible.value) return
        NotificationModel.completion(event.transfer, event.received, host.notificationTexts)?.let { notifications.showCompletion(it) }
    }

    // =============================================================================================================
    // Alarms
    // =============================================================================================================

    /**
     * Wakes the node at the beacon's epoch boundary (N4; the radio's own sets end there) and at the end of "Everyone
     * for 10 min" (F-A5) while the radio session runs: inexact, allowed while idle, no exact-alarm permission.
     */
    private fun rearmAlarms(node: AndroidNode) {
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        val active = foreground.isNotEmpty()
        val epochEnd = node.discovery.advertisement.value?.validUntilMillis
        val epoch = wakeIntent(WAKE_EPOCH)
        if (active && epochEnd != null) {
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochEnd, epoch) }
        } else {
            alarms.cancel(epoch)
        }
        val window = node.visibility.value
        val windowEnd = window.expiresAtMillis?.takeIf { window.mode == Visibility.EVERYONE_TEN_MINUTES }
        val visibility = wakeIntent(WAKE_VISIBILITY)
        if (active && windowEnd != null) {
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, windowEnd, visibility) }
        } else {
            alarms.cancel(visibility)
        }
    }

    private fun cancelAlarms() {
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(wakeIntent(WAKE_EPOCH))
        alarms.cancel(wakeIntent(WAKE_VISIBILITY))
    }

    private fun wakeIntent(which: String): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            serviceIntent(this, ACTION_WAKE, which).setIdentifier("wake:$which"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun serviceTypes(kinds: Set<ForegroundKind>): Int {
        var types = 0
        if (ForegroundKind.CONNECTED_DEVICE in kinds) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (ForegroundKind.DATA_SYNC in kinds) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return types
    }

    /** The secrets and the crypto provider, once per process (`AndroidSecrets.storage` must not be opened twice). */
    private class Shared(
        val secrets: SecretStorage,
        val crypto: AndroidCryptoProvider,
    ) {
        companion object {
            @Volatile
            private var instance: Shared? = null

            fun of(context: Context): Shared =
                instance ?: synchronized(this) {
                    instance ?: Shared(AndroidSecrets.storage(context), AndroidCryptoProvider()).also { instance = it }
                }
        }
    }

    companion object {
        /** A share's read grants for the send that uses them ([holdGrants]). */
        const val ACTION_HOLD_GRANTS: String = "com.constrivo.drop.action.HOLD_GRANTS"

        /** "Accept" on the incoming offer's heads-up ([EXTRA_ID]: the offer id). */
        const val ACTION_ACCEPT: String = "com.constrivo.drop.action.ACCEPT"

        /** "Decline" on the incoming offer's heads-up. */
        const val ACTION_DECLINE: String = "com.constrivo.drop.action.DECLINE"

        /** "Cancel" on the progress notification ([EXTRA_ID]: the transfer id). */
        const val ACTION_CANCEL: String = "com.constrivo.drop.action.CANCEL"

        /** The epoch or visibility alarm. */
        const val ACTION_WAKE: String = "com.constrivo.drop.action.WAKE"

        /** The service starts itself before it goes into the foreground. */
        const val ACTION_PROMOTE: String = "com.constrivo.drop.action.PROMOTE"

        const val EXTRA_ID: String = "com.constrivo.drop.extra.ID"

        private const val TAG = "service"
        private const val WAKE_EPOCH = "epoch"
        private const val WAKE_VISIBILITY = "visibility"
        private const val OFFER_GRACE_MILLIS = 1_000L

        /** The last instance's stop (node and radios); a new instance waits for it before opening them again. */
        private val previousShutdown = AtomicReference<Job?>(null)
        private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The intent to bind with (`BIND_AUTO_CREATE`). */
        fun bindIntent(context: Context): Intent = Intent(context, TransferService::class.java)

        /** An intent for [action] on [id]. */
        fun serviceIntent(
            context: Context,
            action: String,
            id: String?,
        ): Intent =
            Intent(context, TransferService::class.java).setAction(action).apply {
                if (id != null) putExtra(EXTRA_ID, id)
            }

        /**
         * Hands the read grants of [uris] to the service (architecture §10.1 "Share sheet"): the grants a share gave the
         * receiving activity end with it, while a send may outlive it. The service holds them while it is started, which
         * it stays for as long as the send runs or waits for its peer, and 60 s after. Call it from the foreground, before
         * the send starts; `content:` URIs only.
         *
         * @throws IllegalStateException when Android refuses the start (the app is in the background).
         */
        fun holdGrants(
            context: Context,
            uris: List<Uri>,
        ) {
            val content = uris.filter { it.scheme == "content" }.distinct()
            if (content.isEmpty()) return
            val clip = ClipData.newRawUri(null, content.first())
            for (uri in content.drop(1)) clip.addItem(ClipData.Item(uri))
            val intent =
                Intent(context, TransferService::class.java)
                    .setAction(ACTION_HOLD_GRANTS)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            context.startService(intent)
        }
    }
}
