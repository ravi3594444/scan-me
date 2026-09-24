package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.crypto.InMemorySecretStorage
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.openJvm
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconRadio
import com.constrivo.drop.core.discovery.BeaconSighting
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.ladder.StationBand
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.InMemoryDataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.ThermalLevel
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import com.constrivo.drop.platform.android.capability.LocalRadioFacts
import com.constrivo.drop.platform.android.permission.RadioPermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Phones in one JVM: each [AndroidNode] gets a fake beacon radio on a shared "air" (every advertisement is heard by
 * the others every 100 ms, as a scan would), Bluetooth channels as in-memory pipes to the phone at the address the
 * radar heard, an in-memory database, and directory stores. A phone started again under the same name keeps its
 * secrets and files, so it is the same device after an app restart.
 */
internal class PhoneHarness(
    val root: Path = Files.createTempDirectory("drop-phones-"),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val radios = CopyOnWriteArrayList<AirRadio>()
    private val bluetooth = ConcurrentHashMap<String, AirBluetooth>()
    private val secrets = ConcurrentHashMap<String, InMemorySecretStorage>()
    private val nodes = CopyOnWriteArrayList<AndroidNode>()
    private val addresses = AtomicLong()
    val crypto = JcaCryptoProvider()

    /** Problems the phones reported, for failure messages. */
    val problems: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The wall clock every phone reads; tests move it to expire codes. */
    val wallOffset = AtomicLong(0)
    val wall = WallClock { System.currentTimeMillis() + wallOffset.get() }
    val monotonic = MonotonicClock { System.nanoTime() / 1_000_000 }

    init {
        scope.launch {
            while (isActive) {
                broadcast()
                delay(AIR_TICK_MILLIS)
            }
        }
    }

    fun received(name: String): Path = root.resolve(name).resolve("received")

    /** A file of [size] pseudo-random bytes under the harness root. */
    fun file(
        name: String,
        size: Int,
        seed: Int = name.hashCode(),
    ): Path {
        val path = root.resolve("send").resolve(name)
        Files.createDirectories(path.parent)
        Files.write(path, kotlin.random.Random(seed).nextBytes(size))
        return path
    }

    /** Starts the phone [name], visible to everyone, with its radar open. */
    suspend fun phone(
        name: String,
        tuning: NodeTuning = TEST_TUNING,
        visibility: Visibility = Visibility.EVERYONE,
    ): AndroidNode {
        val address = "AA:BB:CC:00:00:%02X".format(addresses.incrementAndGet() and 0xFF)
        val radio = AirRadio(address)
        radios += radio
        val paths = AirBluetooth(address)
        bluetooth[address] = paths
        val base = root.resolve(name)
        Files.createDirectories(base)
        val stores = DirectoryStores(base)
        val database = base.resolve("drop.db").toString()
        val node =
            AndroidNode(
                AndroidNodeConfig(
                    openDatabase = { DropData.openJvm(database, crypto = crypto, clock = wall) },
                    secrets = secrets.computeIfAbsent(name) { InMemorySecretStorage() },
                    crypto = crypto,
                    beaconRadio = radio,
                    bluetooth = paths,
                    stores = stores,
                    power = NoPower,
                    defaultNickname = name,
                    radioFacts = MutableStateFlow(FACTS),
                    radioPermissions = MutableStateFlow(RadioPermissionState.allGranted(34)),
                    wallClock = wall,
                    monotonicClock = monotonic,
                    tuning = tuning,
                    log = { line -> if ("fail" in line || "could not" in line) problems += "$name: $line" },
                ),
            )
        node.start()
        node.setVisibility(visibility)
        node.discovery.setRadarVisible(true)
        nodes += node
        return node
    }

    /** Takes [node] off the air and stops it (an app kill keeps its secrets and files). */
    suspend fun stop(node: AndroidNode) {
        node.stop()
        nodes -= node
        val radio = node.config.beaconRadio as AirRadio
        radios -= radio
        bluetooth.remove(radio.address)
    }

    override fun close() {
        runBlocking { for (node in nodes) runCatching { node.stop() } }
        scope.cancel()
        runCatching { root.toFile().deleteRecursively() }
    }

    private suspend fun broadcast() {
        val now = wall.nowMillis()
        for (from in radios) {
            val advertisement = from.advertisement ?: continue
            if (advertisement.validUntilMillis <= now) continue
            val heard =
                BeaconSighting.fromAdvertisingData(advertisement.advertisingData(), RSSI, from.address, now) ?: continue
            val sighting = BeaconSighting(heard.body, heard.carrier, advertisement.nickname, RSSI, from.address, now)
            for (to in radios) if (to !== from && to.scanning) to.heard.emit(sighting)
        }
    }

    /** The beacon radio of one phone on the shared air. */
    class AirRadio(
        val address: String,
    ) : BeaconRadio {
        @Volatile
        var advertisement: BeaconAdvertisement? = null

        @Volatile
        var scanning = false

        val heard = MutableSharedFlow<BeaconSighting>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        override suspend fun startAdvertising(
            advertisement: BeaconAdvertisement,
            mode: RadioMode,
        ) {
            this.advertisement = advertisement
        }

        override suspend fun stopAdvertising() {
            advertisement = null
        }

        override fun scan(mode: RadioMode): Flow<BeaconSighting> =
            kotlinx.coroutines.flow.flow {
                scanning = true
                try {
                    heard.collect { emit(it) }
                } finally {
                    scanning = false
                }
            }
    }

    /** One phone's Bluetooth: [connect] pipes into the listener of the phone at the address the radar heard. */
    inner class AirBluetooth(
        val address: String,
    ) : BluetoothPaths {
        private val queue = Channel<DataChannel>(QUEUE)

        /** False while the phone is out of range: channels to it fail. */
        @Volatile
        var reachable = true

        override val incoming: ReceiveChannel<DataChannel> get() = queue

        override suspend fun connect(device: NearbyDevice): DataChannel {
            val target =
                device.radioAddresses.firstNotNullOfOrNull { bluetooth[it.address] }
                    ?: throw IOException("nobody answers at ${device.radioAddresses.map { it.address }}")
            if (!target.reachable || !reachable) throw IOException("out of range")
            val (mine, theirs) = InMemoryDataChannel.pair(LinkKind.BLUETOOTH)
            target.queue.send(theirs)
            return mine
        }

        override suspend fun serve() = awaitCancellation()

        override fun refresh() = Unit
    }

    /** Directory stores: sources are paths, received files land in `received/`, partials and plans in `partials/`. */
    class DirectoryStores(
        base: Path,
    ) : NodeStores {
        private val partialRoot = base.resolve("partials")
        val store = DirectoryFileStore(partialRoot, base.resolve("received"))

        override val sources: FileStore get() = store
        override val partials: FileStore get() = store

        override fun receiveStore(saveLocation: String?): FileStore = store

        override fun planDirectory(transferId: TransferId): Path = partialRoot.resolve(transferId.toHex())
    }

    private object NoPower : PowerPolicy {
        override val thermal = MutableStateFlow(ThermalLevel.NONE)

        override fun keepAwake(reason: String): Releasable = Releasable {}
    }

    companion object {
        private const val AIR_TICK_MILLIS = 100L
        private const val RSSI = -50
        private const val QUEUE = 8

        val TEST_TUNING = NodeTuning(finishedRetentionMillis = 600_000, lingerMillis = 300, dialTimeoutMillis = 3_000)

        val FACTS =
            LocalRadioFacts(
                capabilities = Capabilities.NONE,
                networkHint = NetworkHint.NONE,
                stationFrequencyMhz = null,
                stationBand = StationBand.NONE,
                wifiEnabled = false,
                bluetoothAvailable = true,
                bluetoothEnabled = true,
                staApConcurrency = false,
                dualBandSimultaneous = false,
                le2mPhy = true,
            )

        /** Waits until [node]'s radar shows a device called [nickname]. */
        suspend fun seen(
            node: AndroidNode,
            nickname: String,
        ): NearbyDevice = node.devices.first { list -> list.any { it.nickname == nickname } }.first { it.nickname == nickname }

        /** Waits until [node]'s radar shows the device [deviceId] as trusted (its `k_adv` resolves the beacon, S3). */
        suspend fun trusted(
            node: AndroidNode,
            deviceId: String,
        ): NearbyDevice =
            node.devices.first { list ->
                list.any { it.trustedDeviceId == deviceId }
            }.first { it.trustedDeviceId == deviceId }

        /** Waits until transfer [id] on [node] satisfies [condition]. */
        suspend fun transfer(
            node: AndroidNode,
            id: String,
            condition: (NodeTransfer) -> Boolean,
        ): NodeTransfer = node.transfers.first { list -> list.any { it.id == id && condition(it) } }.first { it.id == id }
    }
}
