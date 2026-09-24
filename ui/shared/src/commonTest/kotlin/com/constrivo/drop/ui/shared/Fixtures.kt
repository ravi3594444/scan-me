package com.constrivo.drop.ui.shared

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.DiscoverySource
import com.constrivo.drop.core.discovery.EphemeralId
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.RadarPlacement
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.IncomingOffer
import com.constrivo.drop.ui.shared.model.ItemSummary
import com.constrivo.drop.ui.shared.model.SummaryKind
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TransferStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/** Test data shared by the presenter and screenshot tests. */
object Fixtures {
    const val NOW_UNIX: Long = 1_790_000_000_000L // 2026-09-21T..., a fixed wall clock origin

    fun device(
        key: String,
        nickname: String?,
        ring: Ring = Ring.MIDDLE,
        trusted: Boolean = false,
        platform: DevicePlatform = DevicePlatform.PHONE,
        rssi: Double? = -62.0,
        lanOnly: Boolean = false,
        lastSeen: Long = 0,
    ): NearbyDevice =
        NearbyDevice(
            key = key,
            ephemeralId = EphemeralId((key.hashCode().toLong() and 0xFFFF_FFFFL)),
            trustedDeviceId = if (trusted) "id-$key" else null,
            nickname = nickname,
            nicknameTruncated = false,
            platform = platform,
            visibility = Visibility.EVERYONE,
            capabilities = Capabilities.of(Capabilities.Flag.WIFI_5GHZ, Capabilities.Flag.WIFI_DIRECT),
            networkHint = NetworkHint.NONE,
            ring = ring,
            stableAngleDegrees = RadarPlacement.stableAngleDegrees(key),
            smoothedRssiDbm = if (lanOnly) null else rssi,
            classicAddress = null,
            radioAddresses = emptyList(),
            carrier = null,
            lanEndpoints = emptyList(),
            sources = if (lanOnly) setOf(DiscoverySource.LAN) else setOf(DiscoverySource.BLUETOOTH),
            lastSeenElapsedMillis = lastSeen,
        )

    fun transfer(
        id: String,
        peerKey: String?,
        stage: TransferStage = TransferStage.TRANSFERRING,
        direction: Direction = Direction.SEND,
        bytesDone: Long = 40_000_000,
        bytesTotal: Long = 100_000_000,
        speed: Long? = 44_000_000,
        eta: Long? = 45_000,
        badge: TransportBadge? = TransportBadge.of(LinkKind.P2P, 5180),
        hint: LadderHint? = null,
        count: Int = 12,
        peerName: String = "Rohan's Pixel",
        pairingCode: String? = null,
    ): TransferSnapshot =
        TransferSnapshot(
            id = id,
            peerKey = peerKey,
            peerName = peerName,
            peerPlatform = DevicePlatform.PHONE,
            direction = direction,
            stage = stage,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            bytesPerSecond = speed,
            etaMillis = eta,
            badge = badge,
            hint = hint,
            summary = ItemSummary(count, SummaryKind.PHOTOS),
            thumbnails = List(minOf(count, 10)) { FileThumb.Glyph(FileKind.IMAGE) },
            pairingCode = pairingCode,
        )

    fun offer(
        id: String,
        arrivedAt: Long,
        trusted: Boolean = false,
        sas: String? = "042917",
        count: Int = 12,
        name: String = "Dev",
    ): IncomingOffer =
        IncomingOffer(
            id = id,
            senderKey = "e:$id",
            senderName = name,
            senderPlatform = DevicePlatform.PHONE,
            trusted = trusted,
            sas = sas,
            summary = ItemSummary(count, SummaryKind.PHOTOS),
            totalBytes = 48_000_000,
            previews = List(minOf(count, 8)) { FileThumb.Glyph(FileKind.IMAGE) },
            arrivedAtMillis = arrivedAt,
        )
}

/** The virtual time of a [TestScope] as both clocks (the wall clock offset by [Fixtures.NOW_UNIX]). */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClocks(
    private val scope: TestScope,
) : MonotonicClock,
    WallClock {
    override fun elapsedMillis(): Long = scope.testScheduler.currentTime

    override fun nowMillis(): Long = Fixtures.NOW_UNIX + scope.testScheduler.currentTime
}
