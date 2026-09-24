package com.constrivo.drop.ui.android

import android.util.Log
import com.constrivo.drop.core.data.SettingKey
import com.constrivo.drop.core.data.SettingKeys
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.android.service.AndroidNode
import com.constrivo.drop.platform.android.service.NodeOffer
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.BrowserShareState
import com.constrivo.drop.ui.shared.model.ClearPartialsResult
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryFile
import com.constrivo.drop.ui.shared.model.IncomingOffer
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TrustedDeviceEntry
import com.constrivo.drop.ui.shared.model.VisibilityState
import com.constrivo.drop.ui.shared.presenter.DeviceSource
import com.constrivo.drop.ui.shared.presenter.HistorySource
import com.constrivo.drop.ui.shared.presenter.IncomingActions
import com.constrivo.drop.ui.shared.presenter.LiveActions
import com.constrivo.drop.ui.shared.presenter.MyCode
import com.constrivo.drop.ui.shared.presenter.MyCodeSource
import com.constrivo.drop.ui.shared.presenter.RadarActions
import com.constrivo.drop.ui.shared.presenter.StatsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared UI's engine ports over the [TransferServiceClient]'s node (architecture §10.1 as changed by WP7ef): the
 * radar shows the devices discovery hears, the Live tab and the bubbles the running transfers, the incoming card the
 * waiting offers, and History, trusted devices and Stats read `core/data`. While no node runs (the UI is not bound
 * yet, or the node is starting) every flow shows its empty state and actions do nothing.
 *
 * A send hands its read grants to the service first ([TransferServiceClient.holdGrants]), so a share's files stay
 * readable after the activity that received them is gone. Actions never block the main thread: database work runs in
 * [scope] on [io].
 *
 * @param decodePreview turns an Offer's preview (N12, a stranger's bytes) into a picture, or null to show a glyph.
 * @param openUri opens a stored `content:` URI with the system viewer (History's per-file open).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ServicePorts(
    private val client: TransferServiceClient,
    private val scope: CoroutineScope,
    private val decodePreview: (Preview) -> FileThumb?,
    private val openUri: (uri: String, mime: String?) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : RadarActions,
    IncomingActions,
    LiveActions,
    HistorySource,
    DeviceSource,
    StatsSource,
    MyCodeSource {
    /** Where each received file of this process was published, by tray id (F-D3 open and share onward). */
    val receivedFiles = ReceivedFiles()

    /** Each waiting offer's card, decoded once (its previews are pictures from a stranger, N12). */
    private val offerCards = ConcurrentHashMap<String, IncomingOffer>()

    private val node: AndroidNode? get() = client.node.value

    private fun <T> fromNode(
        empty: T,
        pick: (AndroidNode) -> Flow<T>,
    ): Flow<T> = client.node.flatMapLatest { node -> node?.let(pick) ?: flowOf(empty) }

    val devices: Flow<List<NearbyDevice>> = fromNode(emptyList()) { it.devices }

    val transfers: Flow<List<TransferSnapshot>> = fromNode(emptyList()) { n -> n.transfers.map { list -> list.map(NodeMappers::snapshot) } }

    /** Off the main thread, and once per offer: the offer list changes whenever any offer comes or goes. */
    val offers: Flow<List<IncomingOffer>> = fromNode(emptyList()) { n -> n.offers.map(::incomingOffers).flowOn(compute) }

    val received: Flow<ReceivedFile> =
        client.node
            .flatMapLatest { n -> n?.received ?: emptyFlow() }
            .onEach { receivedFiles.put(it.id, it.uri, it.mimeType) }
            .map(NodeMappers::received)

    val visibility: Flow<VisibilityState> =
        fromNode(VisibilityState(Visibility.TRUSTED_ONLY)) { n -> n.visibility.map(NodeMappers::visibility) }

    /** The cards of [offers]: decoded once per offer id, forgotten once the offer is gone. */
    fun incomingOffers(offers: List<NodeOffer>): List<IncomingOffer> {
        val ids = offers.mapTo(HashSet()) { it.id }
        offerCards.keys.retainAll(ids)
        return offers.map { offer -> offerCards.getOrPut(offer.id) { NodeMappers.incoming(offer, decodePreview) } }
    }

    // ---- RadarActions ----

    override fun send(
        deviceKey: String,
        files: AttachedFiles,
    ) {
        val items = NodeMappers.sendItems(files.items)
        if (items.isEmpty()) return
        client.holdGrants(items.map { it.uri })
        act("send") { send(deviceKey, items) }
    }

    override fun cancel(transferId: String) = act("cancel") { cancel(transferId) }

    override fun confirmPairing(transferId: String) = act("confirm the code") { confirmPairing(transferId) }

    /** "Not now" on the sender's code (F-B3). */
    fun dismissPairing(transferId: String) = act("dismiss the code") { dismissPairing(transferId) }

    // ---- IncomingActions ----

    override fun accept(
        offerId: String,
        alwaysAccept: Boolean,
    ) = act("accept") { accept(offerId, alwaysAccept) }

    override fun decline(offerId: String) = act("decline") { decline(offerId) }

    override fun confirmCode(offerId: String) = act("confirm the code") { confirmCode(offerId) }

    override fun timedOut(offerId: String) = act("time out an offer") { offerTimedOut(offerId) }

    // ---- LiveActions (pausing and adding files are not in the engine; the Live tab offers neither) ----

    override fun pause(transferId: String) = Unit

    override fun resume(transferId: String) = Unit

    override fun addFiles(
        transferId: String,
        files: AttachedFiles,
    ) = Unit

    // ---- HistorySource ----

    override val history: Flow<List<HistoryEntry>>
        get() =
            fromNode(emptyList()) { n ->
                combine(n.data.transfers.observeHistory(HISTORY_ROWS), n.transfers) { records, running ->
                    val live = running.mapTo(HashSet()) { it.id }
                    records.filterNot { it.isActive && it.id.toHex() in live }.map(NodeMappers::history)
                }
            }

    override suspend fun files(transferId: String): List<HistoryFile> {
        val n = node ?: return emptyList()
        val id = parse(transferId) ?: return emptyList()
        return withContext(io) { n.data.transferFiles.files(id).map(NodeMappers::historyFile) }
    }

    override fun openFile(
        transferId: String,
        fileId: String,
    ) {
        val id = parse(transferId) ?: return
        val index = fileId.toIntOrNull() ?: return
        launchIo { n ->
            val file = n.data.transferFiles.file(id, index) ?: return@launchIo
            val uri = file.savedUri?.takeIf(NodeMappers::isOpenable) ?: return@launchIo
            withContext(Dispatchers.Main) { openUri(uri, file.mimeType) }
        }
    }

    override fun resend(transferId: String) = launchIo { it.resend(transferId) }

    override fun clear() = launchIo { it.clearHistory() }

    // ---- DeviceSource ----

    override val trusted: Flow<List<TrustedDeviceEntry>>
        get() = fromNode(emptyList()) { n -> n.data.devices.observeTrusted().map { list -> list.map(NodeMappers::trusted) } }

    override fun rename(
        deviceId: String,
        name: String,
    ) = launchIo { it.rename(deviceId, name.takeIf { n -> n.isNotBlank() }) }

    override fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    ) = launchIo { it.setAutoAccept(deviceId, enabled) }

    override fun forget(deviceId: String) = launchIo { it.forget(deviceId) }

    // ---- StatsSource ----

    override val stats: Flow<StatsSnapshot>
        get() = fromNode(EMPTY_STATS) { n -> n.data.stats.observe().map(NodeMappers::stats) }

    /** Sharing the Stats card as a picture is not built yet; the tab offers it only where the platform does. */
    override fun shareStatsCard() = Unit

    // ---- MyCodeSource (F-B5 five-minute code; F-D6 browser page) ----

    /**
     * A code signed now for five minutes with this phone's identity and beacon ID (a phone has no fixed address to put
     * in it, so it has no fallback to type).
     *
     * @throws IllegalStateException while no node runs (the sheet shows its retry state).
     */
    override suspend fun current(): MyCode {
        val n = checkNotNull(node) { "the transfer service is not running yet" }
        // Showing the code is asking to be found: a Trusted-only phone is invisible to the stranger who scans it, so
        // it becomes visible to everyone for ten minutes (the mode reverts by itself, F-A5).
        if (n.visibility.value.mode == Visibility.TRUSTED_ONLY) {
            withContext(io) { n.setVisibility(Visibility.EVERYONE_TEN_MINUTES) }
        }
        val code = n.oneTimeCode()
        return MyCode(
            code.payload,
            fallbackCode = code.fallback,
            issuedAtMillis = code.issuedAtMillis,
            expiresAtMillis = code.expiresAtMillis,
        )
    }

    override val browserShare: Flow<BrowserShareState>
        get() = fromNode(BrowserShareState.Idle) { n -> n.browserShare.map(NodeMappers::browserShare) }

    override fun startBrowserShare(files: AttachedFiles) {
        val items = NodeMappers.sendItems(files.items)
        if (items.isEmpty()) return
        client.holdGrants(items.map { it.uri })
        act("start the browser page") { startBrowserShare(items) }
    }

    override fun stopBrowserShare() = act("stop the browser page") { stopBrowserShare() }

    // ---- Settings (the rest of SettingsSource is AppGraph's: avatar, nickname, language, save location) ----

    fun setVisibility(mode: Visibility) = launchIo { it.setVisibility(mode) }

    fun <T> setSetting(
        key: SettingKey<T>,
        value: T,
    ) = launchIo { it.setSetting(key, value) }

    fun setNickname(nickname: String) = launchIo { it.setNickname(nickname.takeIf { n -> n.isNotBlank() }) }

    /** "Clear partial files" (F-G5). @throws IllegalStateException while no node runs (the tab shows its failure). */
    suspend fun clearPartialFiles(): ClearPartialsResult {
        val cleared = withContext(io) { checkNotNull(node) { "the transfer service is not running yet" }.clearPartials() }
        return ClearPartialsResult(cleared.transfersCleared, cleared.bytesFreed)
    }

    /** Bundling for the next sends (F-C6, S4). */
    fun setBundleSmallFiles(enabled: Boolean) = setSetting(SettingKeys.BUNDLE_SMALL_FILES, enabled)

    /** Runs [block] on the node now (its actions return at once), when one runs; a failure is logged. */
    private fun act(
        what: String,
        block: AndroidNode.() -> Unit,
    ) {
        val n = node ?: return
        try {
            n.block()
        } catch (e: IllegalStateException) {
            // The node is stopping (the service is being destroyed); the UI rebinds to the next one.
            Log.w(TAG, "could not $what: ${e.message}")
        }
    }

    private fun launchIo(block: suspend (AndroidNode) -> Unit) {
        val n = node ?: return
        scope.launch(io) {
            try {
                block(n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a UI action failed", e)
            }
        }
    }

    private fun parse(transferId: String): TransferId? = runCatching { TransferId.fromHex(transferId) }.getOrNull()

    private companion object {
        const val TAG = "Drop"

        /** History rows kept live (F-G2 pages further back through the repository when needed). */
        const val HISTORY_ROWS = 500

        val EMPTY_STATS = StatsSnapshot(0, null, 0, 0.0, List(StatsSnapshot.WEEKS) { 0 })
    }
}

/**
 * The published location of every file received in this process, by tray id (`<transfer id>:<file index>`), for the
 * tray's open and share onward (F-D3, design §5.2) and the completion notification's "Open". Bounded: the oldest go
 * first. Thread-safe.
 */
internal class ReceivedFiles(
    private val capacity: Int = CAPACITY,
) : ReceivedFileLocator {
    /** Where a received file is and what type it was stored with. */
    data class Location(
        val uri: String,
        val mime: String?,
    )

    private val entries = LinkedHashMap<String, Location>()

    fun put(
        id: String,
        uri: String,
        mime: String?,
    ) {
        synchronized(entries) {
            entries.remove(id)
            entries[id] = Location(uri, mime)
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }

    fun locationOf(id: String): Location? = synchronized(entries) { entries[id] }

    override fun uriOf(fileId: String): android.net.Uri? = locationOf(fileId)?.uri?.let(ReceivedFileLocator.ContentUris::uriOf)

    companion object {
        const val CAPACITY: Int = 2_000
    }
}
