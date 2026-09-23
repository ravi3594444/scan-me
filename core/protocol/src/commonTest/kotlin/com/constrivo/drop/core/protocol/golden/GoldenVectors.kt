package com.constrivo.drop.core.protocol.golden

import com.constrivo.drop.core.protocol.Accept
import com.constrivo.drop.core.protocol.Ack
import com.constrivo.drop.core.protocol.AdvertisingSecret
import com.constrivo.drop.core.protocol.Bundle
import com.constrivo.drop.core.protocol.BundleEntry
import com.constrivo.drop.core.protocol.Bytes
import com.constrivo.drop.core.protocol.Cancel
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ChunkHeader
import com.constrivo.drop.core.protocol.ChunkRef
import com.constrivo.drop.core.protocol.Complete
import com.constrivo.drop.core.protocol.CompleteStatus
import com.constrivo.drop.core.protocol.ControlMessage
import com.constrivo.drop.core.protocol.ControlMoved
import com.constrivo.drop.core.protocol.Decline
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FileDone
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.FileList
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.Heartbeat
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.IndexRange
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.MissingChunks
import com.constrivo.drop.core.protocol.MissingUnits
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.CHUNK_SIZE
import com.constrivo.drop.core.protocol.Resume
import com.constrivo.drop.core.protocol.Retransmit
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.StreamDirection
import com.constrivo.drop.core.protocol.StreamOpen
import com.constrivo.drop.core.protocol.StreamPurpose
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.protocol.WifiCredentials

/**
 * Golden wire encodings (CONTRIBUTING: "every protocol message has a serializer test with golden CBOR bytes").
 *
 * Each vector pairs a fixed instance with the exact bytes it must encode to. The hex was produced by the codec and
 * then checked independently with the Python `cbor2` decoder and by hand against architecture §7. Changing a vector
 * is a wire-format change: it needs a matching note in docs/architecture.md and, if old peers cannot ignore it, a
 * protocol version bump.
 *
 * This file uses only the public API of `core/protocol`, because `tools/fuzz` compiles it too (its seed corpus).
 */
object GoldenVectors {
    class ControlVector(
        val name: String,
        val message: ControlMessage,
        val hex: String,
    )

    class ChunkHeaderVector(
        val name: String,
        val header: ChunkHeader,
        val hex: String,
    )

    class BundleVector(
        val name: String,
        val bundle: Bundle,
        val data: ByteArray,
        val hex: String,
    )

    class FrameVector(
        val name: String,
        val type: FrameType,
        val payloadHex: String,
        val hex: String,
    )

    val TRANSFER_ID: TransferId = TransferId.fromHex("00112233445566778899aabbccddeeff")

    private val P2P_CREDENTIALS = WifiCredentials(ssid = "DIRECT-7F-drop", passphrase = "correct-horse-42")

    val controlMessages: List<ControlVector> =
        listOf(
            ControlVector(
                "offer-minimal",
                Offer(transferId = TRANSFER_ID, fileCount = 1, totalBytes = 1234, bundleCount = 1),
                "8201a7015000112233445566778899aabbccddeeff02010301041904d2081a0040000009f50a01",
            ),
            ControlVector(
                "offer-full",
                Offer(
                    transferId = TRANSFER_ID,
                    fileCount = 12,
                    totalBytes = 48_000_000,
                    mimeHistogram = mapOf("image/png" to 2, "image/jpeg" to 10),
                    previewNames = listOf("IMG_0001.jpg", "IMG_0002.jpg"),
                    previews = listOf(Preview(fileIndex = 0, mime = "image/webp", data = Bytes.fromHex("52494646"))),
                    chunkSize = CHUNK_SIZE,
                    bundleSmall = true,
                    bundleCount = 1,
                    linkOptions =
                        listOf(
                            LinkOption(LinkKind.P2P, credentials = P2P_CREDENTIALS),
                            LinkOption(LinkKind.LAN, address = "192.168.1.20", port = 41234),
                        ),
                ),
                "8201ab015000112233445566778899aabbccddeeff0201030c041a02dc6c0005a269696d6167652f706e67026a696d61" +
                    "67652f6a7065670a06826c494d475f303030312e6a70676c494d475f303030322e6a70670781a30100026a696d616765" +
                    "2f77656270034452494646081a0040000009f50a010b82a2016370327002a2016e4449524543542d37462d64726f7002" +
                    "70636f72726563742d686f7273652d3432a301636c616e036c3139322e3136382e312e32300419a112",
            ),
            ControlVector(
                "file-list",
                FileList(
                    transferId = TRANSFER_ID,
                    page = 0,
                    last = true,
                    files =
                        listOf(
                            FileEntry(
                                index = 0,
                                name = "IMG_0001.jpg",
                                size = 2_500_000,
                                mime = "image/jpeg",
                                modifiedMillis = 1_758_000_000_000,
                            ),
                            FileEntry(index = 1, name = "notes/todo.txt", size = 42),
                        ),
                ),
                "8202a4015000112233445566778899aabbccddeeff020003f50482a50100026c494d475f303030312e6a7067031a0026" +
                    "25a0046a696d6167652f6a706567051b0000019950f72c00a30101026e6e6f7465732f746f646f2e74787403182a",
            ),
            ControlVector(
                "accept-minimal",
                Accept(transferId = TRANSFER_ID, streamCount = 4),
                "8203a2015000112233445566778899aabbccddeeff0404",
            ),
            ControlVector(
                "accept-full",
                Accept(
                    transferId = TRANSFER_ID,
                    link = LinkIntent(LinkKind.HOTSPOT, credentials = WifiCredentials(ssid = "AndroidShare_1234", passphrase = "q8x2m4k9")),
                    resume =
                        MissingUnits(
                            chunks =
                                listOf(
                                    MissingChunks(
                                        fileIndex = 3,
                                        ranges = listOf(IndexRange(0, 2), IndexRange(5, 1)),
                                        firstBlockOffset = 16384,
                                    ),
                                    MissingChunks(fileIndex = BUNDLE_FILE_INDEX, ranges = listOf(IndexRange(1, 2))),
                                ),
                            files = listOf(IndexRange(7, 3)),
                        ),
                    streamCount = 8,
                ),
                "8203a4015000112233445566778899aabbccddeeff02a20167686f7473706f7402a20171416e64726f69645368617265" +
                    "5f313233340268713878326d346b3903a20182a30103028282000282050103194000a2011affffffff02818201020281" +
                    "8207030408",
            ),
            ControlVector("decline", Decline(TRANSFER_ID, DeclineReason.USER), "8204a2015000112233445566778899aabbccddeeff026475736572"),
            ControlVector(
                "ack",
                Ack(
                    TRANSFER_ID,
                    listOf(
                        ChunkRef(fileIndex = 0, chunkIndex = 0),
                        ChunkRef(fileIndex = BUNDLE_FILE_INDEX, chunkIndex = 2),
                        ChunkRef(fileIndex = 4, chunkIndex = 0, blockOffset = 0),
                        ChunkRef(fileIndex = 4, chunkIndex = 0, blockOffset = 16384),
                    ),
                ),
                "8205a2015000112233445566778899aabbccddeeff0284a201000200a2011affffffff0202a3010402000300a3010402" +
                    "0003194000",
            ),
            ControlVector(
                "resume",
                Resume(
                    TRANSFER_ID,
                    MissingUnits(
                        chunks = listOf(MissingChunks(fileIndex = 2, ranges = listOf(IndexRange(0, 1)), firstBlockOffset = 32768)),
                        files = listOf(IndexRange(3, 97)),
                    ),
                ),
                "8206a2015000112233445566778899aabbccddeeff02a20181a30102028182000103198000028182031861",
            ),
            ControlVector(
                "hint-band24",
                Hint(HintCode.BAND24, params = mapOf("freq_mhz" to "2437"), transferId = TRANSFER_ID),
                "8207a3016662616e64323402a168667265715f6d687a6432343337035000112233445566778899aabbccddeeff",
            ),
            ControlVector("hint-minimal", Hint(HintCode.THERMAL), "8207a10167746865726d616c"),
            ControlVector(
                "link-ready",
                LinkReady(
                    LinkKind.P2P,
                    address = "192.168.49.1",
                    port = 41235,
                    freqMhz = 5180,
                    credentials = P2P_CREDENTIALS,
                    generation = 0,
                ),
                "8208a60163703270026c3139322e3136382e34392e310319a1130419143c05a2016e4449524543542d37462d64726f70" +
                    "0270636f72726563742d686f7273652d34320600",
            ),
            ControlVector("heartbeat", Heartbeat(t = 123_456, echo = 123_400), "8209a2011a0001e240021a0001e208"),
            ControlVector(
                "file-done",
                FileDone(
                    TRANSFER_ID,
                    fileIndex = 7,
                    sha256 = Sha256Digest.fromHex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                ),
                "820aa3015000112233445566778899aabbccddeeff0207035820ba7816bf8f01cfea414140de5dae2223b00361a39617" +
                    "7a9cb410ff61f20015ad",
            ),
            ControlVector(
                "complete",
                Complete(TRANSFER_ID, CompleteStatus.OK, bytes = 48_000_000, durationMs = 9_250),
                "820ba4015000112233445566778899aabbccddeeff02626f6b031a02dc6c0004192422",
            ),
            ControlVector(
                "complete-partial",
                Complete(TRANSFER_ID, CompleteStatus.PARTIAL, bytes = 1_000, durationMs = 5_000, failedFiles = listOf(2, 5)),
                "820ba5015000112233445566778899aabbccddeeff02677061727469616c031903e80419138805820205",
            ),
            ControlVector(
                "cancel",
                Cancel(TRANSFER_ID, CancelReason.STORAGE),
                "820ca2015000112233445566778899aabbccddeeff026773746f72616765",
            ),
            ControlVector("control-moved", ControlMoved(streamId = 2, generation = 0), "820da201020200"),
            ControlVector(
                "trust-share",
                TrustShare(AdvertisingSecret(ByteArray(32) { it.toByte() }), generation = 3),
                "820ea2015820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0203",
            ),
            ControlVector(
                "stream-open",
                StreamOpen(
                    TRANSFER_ID,
                    streamId = 2,
                    direction = StreamDirection.SENDER_TO_RECEIVER,
                    purpose = StreamPurpose.CONTROL,
                    generation = 0,
                ),
                "820fa5015000112233445566778899aabbccddeeff020203637332720467636f6e74726f6c0500",
            ),
            ControlVector(
                "retransmit",
                Retransmit(
                    TRANSFER_ID,
                    MissingUnits(
                        chunks = listOf(MissingChunks(fileIndex = 4, ranges = listOf(IndexRange(1, 1)), firstBlockOffset = 49152)),
                    ),
                ),
                "8210a2015000112233445566778899aabbccddeeff02a20181a3010402818201010319c0000280",
            ),
        )

    val chunkHeaders: List<ChunkHeaderVector> =
        listOf(
            ChunkHeaderVector(
                "chunk-wifi",
                ChunkHeader(
                    transferId = TRANSFER_ID,
                    fileIndex = 3,
                    chunkIndex = 1,
                    blockOffset = 0,
                    payloadLength = CHUNK_SIZE,
                    hash = ChunkHash(ByteArray(16) { (0x10 + it).toByte() }),
                ),
                "00112233445566778899aabbccddeeff00000003000000010000000000400000101112131415161718191a1b1c1d1e1f",
            ),
            ChunkHeaderVector(
                "bundle-bluetooth-block",
                ChunkHeader(
                    transferId = TRANSFER_ID,
                    fileIndex = BUNDLE_FILE_INDEX,
                    chunkIndex = 0,
                    blockOffset = 16384,
                    payloadLength = 16384,
                    hash = ChunkHash(ByteArray(16) { (0xF0 + it).toByte() }),
                ),
                "00112233445566778899aabbccddeeffffffffff000000000000400000004000f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            ),
        )

    val bundles: List<BundleVector> =
        listOf(
            BundleVector(
                "bundle-three-files",
                Bundle(0, listOf(BundleEntry(0, 0, 3), BundleEntry(2, 3, 0), BundleEntry(5, 3, 2))),
                "abcde".encodeToByteArray(),
                "000000030000000000000000000000030000000200000003000000000000000500000003000000026162636465",
            ),
        )

    val frames: List<FrameVector> =
        listOf(
            FrameVector("frame-hello", FrameType.HELLO, "010203", "0000000301010203"),
            FrameVector("frame-empty-control", FrameType.CONTROL, "", "0000000010"),
        )

    /** Every encoding above, for seeding fuzzers: (name, bytes). */
    fun allEncodings(): List<Pair<String, ByteArray>> =
        controlMessages.map { it.name to Bytes.fromHex(it.hex).toByteArray() } +
            chunkHeaders.map { it.name to Bytes.fromHex(it.hex).toByteArray() } +
            bundles.map { it.name to Bytes.fromHex(it.hex).toByteArray() } +
            frames.map { it.name to Bytes.fromHex(it.hex).toByteArray() }
}
