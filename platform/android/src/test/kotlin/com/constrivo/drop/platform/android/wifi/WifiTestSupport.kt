package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.net.TcpListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

internal val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

internal fun ip(text: String): InetAddress = IpLiteral.parse(text) ?: error("not an IP literal: $text")

internal fun addr(
    text: String,
    prefix: Int,
): InterfaceAddressInfo = InterfaceAddressInfo(ip(text), prefix)

internal fun iface(
    name: String,
    vararg addresses: InterfaceAddressInfo,
    up: Boolean = true,
    loopback: Boolean = false,
): InterfaceSnapshot = InterfaceSnapshot(name, up, loopback, addresses.toList())

/** The virtual clock of a [TestScope]. */
internal fun TestScope.virtualClock(): MonotonicClock = MonotonicClock { testScheduler.currentTime }

/** Binds every listener to loopback, whatever address the link announces (the addresses of a phone do not exist here). */
internal val LOOPBACK_LISTENERS: LinkListenerFactory =
    LinkListenerFactory { _, kind, io -> TcpListener(InetSocketAddress(LOOPBACK, 0), kind, io) }

/** Records everything a provider reports. */
internal class RecordingListener : WifiLinkListener {
    val frequencies = CopyOnWriteArrayList<Pair<LinkKind, Int>>()
    val lost = CopyOnWriteArrayList<LinkKind>()
    val approvals = CopyOnWriteArrayList<String>()
    val restores = CopyOnWriteArrayList<RestoreReport>()
    val events = CopyOnWriteArrayList<WifiLinkEvent>()

    override fun onFrequencyChanged(
        kind: LinkKind,
        frequencyMhz: Int,
    ) {
        frequencies += kind to frequencyMhz
    }

    override fun onLinkLost(kind: LinkKind) {
        lost += kind
    }

    override fun onJoinApprovalNeeded(ssid: String) {
        approvals += ssid
    }

    override fun onRestore(report: RestoreReport) {
        restores += report
    }

    override fun onEvent(event: WifiLinkEvent) {
        events += event
    }
}

/** A station that tests connect and disconnect. */
internal class FakeStation(
    connected: Boolean,
) : StationMonitor {
    private val state = MutableStateFlow(connected)
    override val connected: StateFlow<Boolean> = state.asStateFlow()

    fun set(connected: Boolean) {
        state.value = connected
    }
}

/** Interfaces the test sets; [reads] counts the snapshots taken. */
internal class FakeInterfaces(
    var snapshot: () -> List<InterfaceSnapshot>,
) : InterfaceLookup {
    var reads = 0

    override fun snapshot(): List<InterfaceSnapshot> {
        reads++
        return snapshot.invoke()
    }
}

/** A socket binder that records the sockets it bound. */
internal class RecordingBinder : SocketBinder {
    val bound = CopyOnWriteArrayList<Socket>()
    var failure: java.io.IOException? = null

    override fun bind(socket: Socket) {
        failure?.let { throw it }
        bound += socket
    }
}

/**
 * A `ConnectivityManager` for specifier requests: [script] is delivered as soon as a request is filed, [emit] sends more
 * later; [released] counts the handles released (each handle counts once, as the platform one is idempotent).
 */
internal class FakeRequester : NetworkRequester {
    val requests = CopyOnWriteArrayList<SpecifierRequestSpec>()
    val script = CopyOnWriteArrayList<NetworkEvent>()
    val binder = RecordingBinder()
    var failure: RuntimeException? = null
    var released = 0
    private var sink: ((NetworkEvent) -> Unit)? = null

    /** Scripts a network that comes up with [addresses] and [frequencyMhz]. */
    fun autoUp(
        handle: Long = 100,
        frequencyMhz: Int? = 2437,
        addresses: List<InterfaceAddressInfo> = listOf(addr("127.0.0.1", 8)),
        interfaceName: String = "wlan1",
    ) {
        script += NetworkEvent.Available(handle, binder)
        script += NetworkEvent.CapabilitiesChanged(handle, frequencyMhz)
        script += NetworkEvent.LinkPropertiesChanged(handle, interfaceName, addresses)
    }

    fun emit(event: NetworkEvent) {
        sink?.invoke(event)
    }

    override fun request(
        spec: SpecifierRequestSpec,
        events: (NetworkEvent) -> Unit,
    ): NetworkRequestHandle {
        failure?.let { throw it }
        requests += spec
        sink = events
        script.forEach(events)
        var done = false
        return NetworkRequestHandle {
            if (!done) {
                done = true
                released++
                sink = null
            }
        }
    }
}

/**
 * A Wi-Fi Direct framework in memory, on the test's virtual time ([scope]): `createGroup` and `connect` form a group
 * after [formationDelayMillis], `removeGroup` ends it, the broadcasts ([broadcasts]) and queries ([answerQueries])
 * report it. Tests script failures and silences through the public fields.
 */
internal class FakeP2pRadio(
    private val scope: CoroutineScope,
    override val isAvailable: Boolean = true,
) : P2pRadio {
    private val mutable = MutableStateFlow(P2pRadioState(enabled = true))
    override val state: StateFlow<P2pRadioState> = mutable.asStateFlow()

    var group: P2pGroupSnapshot? = null
    var connection: P2pConnectionSnapshot = P2pConnectionSnapshot(false, false, null)

    /** Answer of `createGroup`; null: never answers. */
    var createResult: P2pActionResult? = P2pActionResult.Success

    /** Answers of successive `connect` calls (then success); a null entry never answers. */
    val connectResults = ArrayDeque<P2pActionResult?>()

    /** `connect` forms a group from this attempt on (1-based); later attempts than those listed form one too. */
    var connectFormsFromAttempt = 1
    var formsGroup = true
    var formationDelayMillis = 500L
    var broadcasts = true
    var answerQueries = true
    var answerActions = true
    var frequencyMhz: Int? = 5180
    var ownerAddress: InetAddress = LOOPBACK
    var ownerInterface = "p2p-wlan0-0"
    var clientInterface = "p2p-wlan0-1"

    /** When set, the group comes up under this name and passphrase instead of the requested ones. */
    var actualNetworkName: String? = null
    var actualPassphrase: String? = null
    var throwOnCall: RuntimeException? = null

    val calls = CopyOnWriteArrayList<String>()
    val specs = CopyOnWriteArrayList<P2pGroupSpec>()
    private var connectAttempts = 0
    private var pending: Job? = null

    fun setEnabled(enabled: Boolean) {
        mutable.update { it.copy(enabled = enabled, sequence = it.sequence + 1) }
    }

    /** The system ends the group (out of range, Wi-Fi off). */
    fun dropGroup() {
        group = null
        connection = P2pConnectionSnapshot(false, false, null)
        broadcast()
    }

    fun changeFrequency(frequencyMhz: Int) {
        group = group?.copy(frequencyMhz = frequencyMhz)
        broadcast()
    }

    /** A group that exists before the test starts (another app's, or this app's from an earlier run). */
    fun presetGroup(
        networkName: String,
        owner: Boolean = true,
    ) {
        group = P2pGroupSnapshot(networkName, "passphrase-x", 2437, ownerInterface, owner)
        connection = P2pConnectionSnapshot(true, owner, ownerAddress)
    }

    fun broadcast() {
        if (broadcasts) mutable.update { it.copy(connection = connection, group = group, sequence = it.sequence + 1) }
    }

    override fun createGroup(
        spec: P2pGroupSpec,
        done: (P2pActionResult) -> Unit,
    ) {
        throwOnCall?.let { throw it }
        calls += "createGroup"
        specs += spec
        val result = createResult ?: return
        if (answerActions) done(result)
        if (result is P2pActionResult.Success && formsGroup) {
            pending =
                scope.launch {
                    delay(formationDelayMillis)
                    group =
                        P2pGroupSnapshot(
                            actualNetworkName ?: spec.networkName,
                            actualPassphrase ?: spec.passphrase,
                            frequencyMhz,
                            ownerInterface,
                            true,
                        )
                    connection = P2pConnectionSnapshot(true, true, ownerAddress)
                    broadcast()
                }
        }
    }

    override fun connect(
        spec: P2pGroupSpec,
        done: (P2pActionResult) -> Unit,
    ) {
        throwOnCall?.let { throw it }
        calls += "connect"
        specs += spec
        connectAttempts++
        val result = if (connectResults.isEmpty()) P2pActionResult.Success else connectResults.removeFirst() ?: return
        if (answerActions) done(result)
        if (result is P2pActionResult.Success && formsGroup && connectAttempts >= connectFormsFromAttempt) {
            pending =
                scope.launch {
                    delay(formationDelayMillis)
                    group = P2pGroupSnapshot(spec.networkName, null, frequencyMhz, clientInterface, false)
                    connection = P2pConnectionSnapshot(true, false, ownerAddress)
                    broadcast()
                }
        }
    }

    override fun cancelConnect(done: (P2pActionResult) -> Unit) {
        calls += "cancelConnect"
        pending?.cancel()
        if (answerActions) done(P2pActionResult.Success)
    }

    override fun removeGroup(done: (P2pActionResult) -> Unit) {
        calls += "removeGroup"
        pending?.cancel()
        val had = group != null || connection.groupFormed
        group = null
        connection = P2pConnectionSnapshot(false, false, null)
        broadcast()
        if (answerActions) done(if (had) P2pActionResult.Success else P2pActionResult.Failure(P2pFailureCodes.ERROR))
    }

    override fun requestGroupInfo(done: (P2pGroupSnapshot?) -> Unit) {
        if (answerQueries) done(group)
    }

    override fun requestConnectionInfo(done: (P2pConnectionSnapshot?) -> Unit) {
        if (answerQueries) done(connection)
    }

    /** What `requestP2pState` answers, independent of the broadcast state (which may not have arrived). */
    var framework: Boolean = true

    override fun requestEnabled(done: (Boolean?) -> Unit) {
        if (answerQueries) done(framework)
    }

    /** Clears the broadcast state, as before the first state broadcast arrives. */
    fun forgetBroadcasts() {
        mutable.update { P2pRadioState(sequence = it.sequence + 1) }
    }
}
