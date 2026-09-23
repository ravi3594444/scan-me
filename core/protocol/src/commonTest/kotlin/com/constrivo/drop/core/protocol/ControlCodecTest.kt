package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.RawCbor.array
import com.constrivo.drop.core.protocol.RawCbor.bytes
import com.constrivo.drop.core.protocol.RawCbor.envelope
import com.constrivo.drop.core.protocol.RawCbor.map
import com.constrivo.drop.core.protocol.RawCbor.text
import com.constrivo.drop.core.protocol.RawCbor.uint
import com.constrivo.drop.core.protocol.golden.GoldenVectors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlCodecTest {
    private val id = bytes(TEST_ID.toByteArray())

    @Test
    fun roundTripsVariedMessages() {
        val messages =
            listOf(
                Offer(
                    TEST_ID,
                    fileCount = 100_000,
                    totalBytes = Long.MAX_VALUE,
                    chunkSize = ProtocolConstants.MIN_CHUNK_SIZE,
                    bundleSmall = false,
                    bundleCount = 0,
                ),
                FileList(TEST_ID, page = 7, last = false, files = List(50) { FileEntry(700 + it, "dir/ü-файл-$it.bin", it * 1_000_000L) }),
                Accept(TEST_ID, resume = MissingUnits.NONE, streamCount = 1),
                Ack(TEST_ID, List(ProtocolConstants.MAX_ACK_REFS) { ChunkRef(it % 7, it) }),
                Heartbeat(0),
                Hint("future_hint_code", mapOf("k" to "")),
                LinkReady("lan", freqMhz = 0, generation = 12),
                Complete(TEST_ID, CompleteStatus.FAILED, 0, 0, failedFiles = listOf(0, 1, 99_999)),
                StreamOpen(OTHER_ID, Int.MAX_VALUE, StreamDirection.RECEIVER_TO_SENDER, StreamPurpose.DATA, Int.MAX_VALUE),
                Retransmit(TEST_ID, MissingUnits(files = listOf(IndexRange(3, 2)))),
            )
        for (message in messages) assertEquals(message, ControlCodec.decode(ControlCodec.encode(message)))
    }

    @Test
    fun anEmptyRetransmitIsRejectedOnDecode() {
        val id = RawCbor.bytes(TEST_ID.toByteArray())
        val empty = RawCbor.map(RawCbor.uint(1) to RawCbor.array(), RawCbor.uint(2) to RawCbor.array())
        assertProtocolError { ControlCodec.decode(RawCbor.envelope(16, RawCbor.map(RawCbor.uint(1) to id, RawCbor.uint(2) to empty))) }
    }

    @Test
    fun unknownBodyKeysAreIgnored() {
        // Heartbeat {1: 5, 99: [ {"x": h'00'}, -3, 1.5, null ], 3: "new field"}
        val future =
            map(
                uint(1) to uint(5),
                uint(99) to array(map(text("x") to bytes(byteArrayOf(0))), byteArrayOf(0x22), "f93e00".unhex(), byteArrayOf(0xF6.toByte())),
                uint(3) to text("new field"),
            )
        assertEquals(Heartbeat(5), ControlCodec.decode(envelope(ControlMessageType.HEARTBEAT.code, future)))
    }

    @Test
    fun unknownKeysInNestedRecordsAreIgnored() {
        val option = map(uint(1) to text("p2p"), uint(9) to text("future"))
        val body =
            map(
                uint(1) to id,
                uint(3) to uint(1),
                uint(4) to uint(10),
                uint(10) to uint(1),
                uint(11) to array(option),
            )
        val offer = assertIs<Offer>(ControlCodec.decode(envelope(1, body)))
        assertEquals(listOf(LinkOption(LinkKind.P2P)), offer.linkOptions)
        // Absent fields take their defaults.
        assertEquals(ProtocolConstants.PROTOCOL_VERSION, offer.version)
        assertEquals(ProtocolConstants.CHUNK_SIZE, offer.chunkSize)
        assertTrue(offer.bundleSmall)
    }

    @Test
    fun unknownMessageTypeIsReportedSeparately() {
        val e = assertProtocolError { ControlCodec.decode(envelope(200, map(uint(1) to uint(1)))) }
        assertIs<UnknownControlMessageException>(e)
        assertEquals(200, e.code)
    }

    @Test
    fun unknownEnumNamesUseTheirFallbacks() {
        val decline = ControlCodec.decode(envelope(4, map(uint(1) to id, uint(2) to text("quota_exceeded"))))
        assertEquals(Decline(TEST_ID, DeclineReason.OTHER), decline)
        val cancel = ControlCodec.decode(envelope(12, map(uint(1) to id, uint(2) to text("aliens"))))
        assertEquals(Cancel(TEST_ID, CancelReason.OTHER), cancel)
        val complete =
            ControlCodec.decode(
                envelope(11, map(uint(1) to id, uint(2) to text("mostly"), uint(3) to uint(1), uint(4) to uint(1))),
            )
        assertEquals(CompleteStatus.FAILED, assertIs<Complete>(complete).status)
        val purpose =
            ControlCodec.decode(
                envelope(15, map(uint(1) to id, uint(2) to uint(2), uint(3) to text("s2r"), uint(4) to text("video"), uint(5) to uint(0))),
            )
        assertEquals(StreamPurpose.DATA, assertIs<StreamOpen>(purpose).purpose)
        // A direction is essential: an unknown one is an error.
        assertProtocolError {
            ControlCodec.decode(
                envelope(
                    15,
                    map(
                        uint(1) to id,
                        uint(2) to uint(2),
                        uint(3) to text("sideways"),
                        uint(4) to text("data"),
                        uint(5) to uint(0),
                    ),
                ),
            )
        }
    }

    @Test
    fun unknownLinkKindsAndHintCodesSurviveAsStrings() {
        val future = LinkReady("aware", freqMhz = 5745, generation = 1)
        val ready = assertIs<LinkReady>(ControlCodec.decode(ControlCodec.encode(future)))
        assertEquals("aware", ready.kind)
        assertNull(ready.linkKind)
        assertEquals(LinkKind.P2P, LinkReady(LinkKind.P2P, freqMhz = 1, generation = 0).linkKind)
        val hint = assertIs<Hint>(ControlCodec.decode(ControlCodec.encode(Hint("newer_hint"))))
        assertNull(hint.hintCode)
        assertEquals(HintCode.SDCARD, Hint(HintCode.SDCARD).hintCode)
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        assertProtocolError { ControlCodec.decode(envelope(9, map())) } // Heartbeat without t
        assertProtocolError { ControlCodec.decode(envelope(12, map(uint(1) to id))) } // Cancel without reason
        val offerWithoutBundleCount = envelope(1, map(uint(1) to id, uint(3) to uint(1), uint(4) to uint(1)))
        assertProtocolError { ControlCodec.decode(offerWithoutBundleCount) }
    }

    @Test
    fun wrongTypesAreRejected() {
        assertProtocolError { ControlCodec.decode(envelope(9, map(uint(1) to text("soon")))) }
        assertProtocolError { ControlCodec.decode(envelope(12, map(uint(1) to text("id"), uint(2) to text("user")))) }
        // A transfer id must be a 16-byte byte string, not an array of integers or 15 bytes.
        assertProtocolError { ControlCodec.decode(envelope(12, map(uint(1) to array(uint(1)), uint(2) to text("user")))) }
        assertProtocolError { ControlCodec.decode(envelope(12, map(uint(1) to bytes(ByteArray(15)), uint(2) to text("user")))) }
        // Int overflow: stream_count as 2^40.
        assertProtocolError { ControlCodec.decode(envelope(3, map(uint(1) to id, uint(4) to uint(1L shl 40)))) }
        // IndexRange is a two-element array; three elements are rejected.
        val badRange = map(uint(1) to array(map(uint(1) to uint(0), uint(2) to array(array(uint(0), uint(1), uint(2))))))
        assertProtocolError { ControlCodec.decode(envelope(6, map(uint(1) to id, uint(2) to badRange))) }
    }

    @Test
    fun invariantsAreCheckedOnDecode() {
        fun offer(vararg extra: Pair<ByteArray, ByteArray>) =
            envelope(1, map(uint(1) to id, uint(3) to uint(1), uint(4) to uint(1), *extra, uint(10) to uint(0)))
        assertProtocolError("file_count 0") {
            ControlCodec.decode(
                envelope(
                    1,
                    map(
                        uint(1) to id,
                        uint(3) to uint(0),
                        uint(4) to uint(1),
                        uint(10) to uint(0),
                    ),
                ),
            )
        }
        assertProtocolError("chunk size not a power of two") { ControlCodec.decode(offer(uint(8) to uint(3_000_000))) }
        assertProtocolError("chunk size above 4 MiB") { ControlCodec.decode(offer(uint(8) to uint(8L * 1024 * 1024))) }
        val bigPreview =
            map(
                uint(1) to uint(0),
                uint(2) to text("image/jpeg"),
                uint(3) to bytes(ByteArray(ProtocolConstants.MAX_PREVIEW_BYTES + 1)),
            )
        assertProtocolError("preview over 4 KiB") { ControlCodec.decode(offer(uint(7) to array(bigPreview))) }
        assertProtocolError("stream_count 9") { ControlCodec.decode(envelope(3, map(uint(1) to id, uint(4) to uint(9)))) }
        assertProtocolError("stream id 0") {
            ControlCodec.decode(
                envelope(
                    15,
                    map(
                        uint(1) to id,
                        uint(2) to uint(0),
                        uint(3) to text("s2r"),
                        uint(4) to text("data"),
                        uint(5) to uint(0),
                    ),
                ),
            )
        }
        // FileDone may not name the bundle marker.
        val sha = bytes(ByteArray(32))
        assertProtocolError("bundle in FileDone") {
            ControlCodec.decode(
                envelope(
                    10,
                    map(
                        uint(1) to id,
                        uint(2) to uint(0xFFFF_FFFFL),
                        uint(3) to sha,
                    ),
                ),
            )
        }
    }

    @Test
    fun bundleFileIndexUsesTheU32Marker() {
        val ack = Ack(TEST_ID, listOf(ChunkRef(ProtocolConstants.BUNDLE_FILE_INDEX, 3)))
        val bytes = ControlCodec.encode(ack)
        assertTrue(bytes.hex().contains("1affffffff"), "0xFFFFFFFF on the wire")
        assertEquals(ack, ControlCodec.decode(bytes))
        // Values between the file limit and the marker are rejected.
        val body = map(uint(1) to id, uint(2) to array(map(uint(1) to uint(0xFFFF_FFFEL), uint(2) to uint(0))))
        assertProtocolError { ControlCodec.decode(envelope(5, body)) }
    }

    @Test
    fun malformedEnvelopesAreRejected() {
        assertProtocolError("empty") { ControlCodec.decode(ByteArray(0)) }
        assertProtocolError("not an array") { ControlCodec.decode(map(uint(1) to uint(1))) }
        assertProtocolError("one element") { ControlCodec.decode(array(uint(9))) }
        assertProtocolError("three elements") { ControlCodec.decode(array(uint(9), map(uint(1) to uint(1)), uint(0))) }
        assertProtocolError("type as text") { ControlCodec.decode(array(text("hb"), map(uint(1) to uint(1)))) }
        assertProtocolError("body not a map") { ControlCodec.decode(array(uint(9), array(uint(1)))) }
        assertProtocolError("trailing byte") { ControlCodec.decode(envelope(9, map(uint(1) to uint(1))) + byteArrayOf(0)) }
        assertProtocolError("truncated") { ControlCodec.decode(GoldenVectors.controlMessages[1].hex.unhex().copyOf(40)) }
    }

    @Test
    fun cborProfileViolationsAreRejected() {
        val good = envelope(9, map(uint(1) to uint(1)))
        assertEquals(Heartbeat(1), ControlCodec.decode(good))
        assertProtocolError("indefinite map") { ControlCodec.decode("8209bf0101ff".unhex()) }
        assertProtocolError("tag") { ControlCodec.decode("8209a101c11a5f5e1000".unhex()) }
        assertProtocolError("non-shortest int") { ControlCodec.decode("8209a1011801".unhex()) }
        assertProtocolError("duplicate key") { ControlCodec.decode("8209a201010102".unhex()) }
        val alias = map(uint(1) to id, uint(2) to text("user"), text("transferId") to bytes(OTHER_ID.toByteArray()))
        assertProtocolError("text key aliasing a labelled field") { ControlCodec.decode(envelope(12, alias)) }
        assertProtocolError("invalid UTF-8") { ControlCodec.decode(envelope(7, map(uint(1) to "62c328".unhex()))) }
        assertProtocolError("huge string length") { ControlCodec.decode("8207a1017bffffffffffffffff".unhex()) }
        assertProtocolError("huge array length") { ControlCodec.decode("8205a2025000112233445566778899aabbccddeeff029affffffff".unhex()) }
        assertProtocolError("uint64 max as file index") {
            ControlCodec.decode("8205a2015000112233445566778899aabbccddeeff0281a2011bffffffffffffffff0200".unhex())
        }
        var deep = uint(0)
        repeat(ProtocolConstants.MAX_CBOR_DEPTH) { deep = array(deep) }
        assertProtocolError("deep nesting") { ControlCodec.decode(envelope(9, map(uint(1) to uint(1), uint(9) to deep))) }
    }

    @Test
    fun oversizedMessagesAreRejectedBothWays() {
        val names =
            List(ProtocolConstants.MAX_FILE_LIST_PAGE_ENTRIES) { FileEntry(it, "x".repeat(ProtocolConstants.MAX_FILE_NAME_BYTES), 1) }
        assertProtocolError("encode") { ControlCodec.encode(FileList(TEST_ID, 0, true, names)) }
        assertProtocolError("decode") { ControlCodec.decode(ByteArray(ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES + 1)) }
        assertProtocolError("range") { ControlCodec.decode(ByteArray(4), 2, 3) }
    }

    @Test
    fun decodesFromAnOffset() {
        val golden = GoldenVectors.controlMessages.first { it.name == "cancel" }
        val bytes = golden.hex.unhex()
        val padded = byteArrayOf(9, 9) + bytes + byteArrayOf(7)
        assertEquals(golden.message, ControlCodec.decode(padded, 2, bytes.size))
    }

    @Test
    fun randomGarbageOnlyEverRaisesProtocolException() {
        val random = Random(20260923)
        repeat(20_000) {
            val size = random.nextInt(0, 64)
            val garbage = random.nextBytes(size)
            if (random.nextBoolean() && size > 2) {
                garbage[0] = 0x82.toByte()
                garbage[1] = random.nextInt(0, 16).toByte()
                garbage[2] = (0xA0 + random.nextInt(0, 6)).toByte()
            }
            try {
                ControlCodec.decode(garbage)
            } catch (e: ProtocolException) {
                // expected
            }
        }
    }

    @Test
    fun mutatedGoldensOnlyEverRaiseProtocolException() {
        val random = Random(7)
        for (vector in GoldenVectors.controlMessages) {
            val golden = vector.hex.unhex()
            repeat(500) {
                val mutated = golden.copyOf()
                repeat(1 + random.nextInt(3)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
                try {
                    val decoded = ControlCodec.decode(mutated)
                    // Whatever decodes must re-encode and decode to the same message.
                    assertEquals(decoded, ControlCodec.decode(ControlCodec.encode(decoded)))
                } catch (e: ProtocolException) {
                    // expected
                }
            }
        }
    }

    @Test
    fun secretsAreNotPrinted() {
        val credentials = WifiCredentials("DIRECT-ab-x", "super-secret-pass")
        assertFalse(credentials.toString().contains("super-secret-pass"))
        assertFalse(
            LinkReady(LinkKind.HOTSPOT, freqMhz = 2412, credentials = credentials, generation = 0).toString().contains("super-secret-pass"),
        )
        val secret = AdvertisingSecret(ByteArray(32) { 0x5A })
        assertFalse(TrustShare(secret, 1).toString().contains("5a5a"))
    }

    @Test
    fun constructorsRejectInvalidArguments() {
        assertRejectsArgument { Offer(TEST_ID, fileCount = 1, totalBytes = -1, bundleCount = 0) }
        assertRejectsArgument { Offer(TEST_ID, fileCount = 2, totalBytes = 1, mimeHistogram = mapOf("a/b" to 3), bundleCount = 0) }
        assertRejectsArgument { Offer(TEST_ID, fileCount = 1, totalBytes = 1, previewNames = listOf("a", "b"), bundleCount = 0) }
        assertRejectsArgument { Offer(TEST_ID, fileCount = 2, totalBytes = 1, bundleSmall = false, bundleCount = 1) }
        assertRejectsArgument { Offer(TEST_ID, fileCount = 2, totalBytes = 1, bundleCount = 3) }
        val preview = Preview(0, "image/png", Bytes(byteArrayOf(1)))
        assertRejectsArgument { Offer(TEST_ID, fileCount = 2, totalBytes = 1, previews = listOf(preview, preview), bundleCount = 0) }
        assertRejectsArgument { FileList(TEST_ID, 0, true, emptyList()) }
        assertRejectsArgument { FileList(TEST_ID, 0, true, listOf(FileEntry(2, "a", 1), FileEntry(1, "b", 1))) }
        assertRejectsArgument { FileEntry(0, "", 1) }
        assertRejectsArgument { FileEntry(0, "bad\uD800", 1) }
        assertRejectsArgument { FileEntry(ProtocolConstants.MAX_FILES_PER_TRANSFER, "a", 1) }
        assertRejectsArgument { Ack(TEST_ID, emptyList()) }
        assertRejectsArgument { ChunkRef(-2, 0) }
        assertRejectsArgument { ChunkRef(0, 0, blockOffset = ProtocolConstants.CHUNK_SIZE) }
        assertRejectsArgument { WifiCredentials("", "12345678") }
        assertRejectsArgument { WifiCredentials("ssid", "short") }
        assertRejectsArgument { LinkOption(LinkKind.LAN, port = 70_000) }
        assertRejectsArgument { LinkReady(LinkKind.P2P, freqMhz = -1, generation = 0) }
        assertRejectsArgument { Heartbeat(-1) }
        assertRejectsArgument { Complete(TEST_ID, CompleteStatus.OK, 1, 1, failedFiles = listOf(1)) }
        assertRejectsArgument { Complete(TEST_ID, CompleteStatus.PARTIAL, 1, 1, failedFiles = listOf(3, 3)) }
        assertRejectsArgument { ControlMoved(-1, 0) }
        assertRejectsArgument { StreamOpen(TEST_ID, 0, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, 0) }
        assertRejectsArgument { StreamOpen(TEST_ID, 1, StreamDirection.SENDER_TO_RECEIVER, StreamPurpose.DATA, 0) }
        assertRejectsArgument { Retransmit(TEST_ID, MissingUnits.NONE) }
        assertRejectsArgument { Hint("x".repeat(ProtocolConstants.MAX_WIRE_NAME_BYTES + 1)) }
        assertRejectsArgument { Hint(HintCode.THERMAL, params = (0..ProtocolConstants.MAX_HINT_PARAMS).associate { "k$it" to "v" }) }
        assertRejectsArgument { TransferId(ByteArray(15)) }
        assertRejectsArgument { Sha256Digest(ByteArray(31)) }
    }

    @Test
    fun missingUnitsInvariants() {
        assertRejectsArgument { MissingChunks(0, emptyList()) }
        assertRejectsArgument { MissingChunks(0, listOf(IndexRange(0, 2), IndexRange(2, 1))) } // adjacent: not coalesced
        assertRejectsArgument { MissingChunks(0, listOf(IndexRange(5, 1), IndexRange(0, 1))) }
        assertRejectsArgument {
            MissingUnits(
                chunks =
                    listOf(
                        MissingChunks(ProtocolConstants.BUNDLE_FILE_INDEX, listOf(IndexRange(0, 1))),
                        MissingChunks(0, listOf(IndexRange(0, 1))),
                    ),
            )
        }
        assertRejectsArgument {
            MissingUnits(chunks = listOf(MissingChunks(4, listOf(IndexRange(0, 1)))), files = listOf(IndexRange(3, 2)))
        }
        assertRejectsArgument { MissingUnits(files = listOf(IndexRange(0, 2), IndexRange(2, 2))) }
        assertRejectsArgument { IndexRange(-1, 1) }
        assertRejectsArgument { IndexRange(0, 0) }
        assertRejectsArgument { IndexRange(Int.MAX_VALUE, 1) }
        // Bundle entry last (0xFFFFFFFF sorts after every file index); a file range next to a chunk entry is fine.
        MissingUnits(
            chunks = listOf(MissingChunks(4, listOf(IndexRange(0, 1))), MissingChunks(-1, listOf(IndexRange(0, 1)))),
            files = listOf(IndexRange(5, 2)),
        )
    }

    @Test
    fun indexRangeCoalescing() {
        assertEquals(emptyList(), IndexRange.coalesce(emptyList()))
        assertEquals(listOf(IndexRange(0, 3), IndexRange(5, 1), IndexRange(7, 2)), IndexRange.coalesce(listOf(0, 1, 2, 5, 7, 8)))
        assertRejectsArgument { IndexRange.coalesce(listOf(3, 2)) }
        assertRejectsArgument { IndexRange.coalesce(listOf(3, 3)) }
        assertTrue(4 in IndexRange(3, 2))
        assertFalse(5 in IndexRange(3, 2))
    }
}
