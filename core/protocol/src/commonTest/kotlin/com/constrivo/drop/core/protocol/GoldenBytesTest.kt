package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.golden.GoldenVectors
import kotlinx.serialization.cbor.CborLabel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-E9 / F-E6: exact wire bytes for every control message, the chunk header, the bundle index and frames. */
class GoldenBytesTest {
    @Test
    fun everyControlMessageTypeHasAGoldenVector() {
        val covered = GoldenVectors.controlMessages.map { it.message.type }.toSet()
        assertEquals(ControlMessageType.entries.toSet(), covered)
    }

    @Test
    fun controlMessagesEncodeToTheGoldenBytes() {
        for (vector in GoldenVectors.controlMessages) {
            assertEquals(vector.hex, ControlCodec.encode(vector.message).hex(), "encoding of ${vector.name}")
        }
    }

    @Test
    fun goldenBytesDecodeToTheGoldenMessages() {
        for (vector in GoldenVectors.controlMessages) {
            assertEquals(vector.message, ControlCodec.decode(vector.hex.unhex()), "decoding of ${vector.name}")
        }
    }

    @Test
    fun goldenBytesAreCanonicalCbor() {
        for (vector in GoldenVectors.controlMessages) {
            CborProfile.validate(vector.hex.unhex(), canonical = true)
        }
    }

    @Test
    fun envelopeStartsWithTheTypeCode() {
        for (vector in GoldenVectors.controlMessages) {
            val bytes = vector.hex.unhex()
            assertEquals(0x82, bytes[0].toInt() and 0xFF, vector.name)
            assertEquals(vector.message.type.code, bytes[1].toInt(), vector.name)
        }
    }

    @Test
    fun encodingIsDeterministicAndIndependentOfMapInsertionOrder() {
        val a =
            Offer(
                TEST_ID,
                fileCount = 3,
                totalBytes = 3,
                mimeHistogram = linkedMapOf("image/png" to 1, "text/plain" to 1, "a/b" to 1),
                bundleCount = 1,
            )
        val b = a.copy(mimeHistogram = linkedMapOf("a/b" to 1, "text/plain" to 1, "image/png" to 1))
        assertContentEquals(ControlCodec.encode(a), ControlCodec.encode(b))
        assertContentEquals(ControlCodec.encode(a), ControlCodec.encode(a))
        val h1 = Hint(HintCode.LAN_SLOW, mapOf("zz" to "1", "a" to "2", "mm" to "3"))
        val h2 = Hint(HintCode.LAN_SLOW, mapOf("mm" to "3", "zz" to "1", "a" to "2"))
        assertContentEquals(ControlCodec.encode(h1), ControlCodec.encode(h2))
    }

    @Test
    fun everySerializedFieldCarriesAnIntegerLabel() {
        val descriptors =
            listOf(
                Offer.serializer(),
                FileList.serializer(),
                Accept.serializer(),
                Decline.serializer(),
                Ack.serializer(),
                Resume.serializer(),
                Hint.serializer(),
                LinkReady.serializer(),
                Heartbeat.serializer(),
                FileDone.serializer(),
                Complete.serializer(),
                Cancel.serializer(),
                ControlMoved.serializer(),
                TrustShare.serializer(),
                StreamOpen.serializer(),
                WifiCredentials.serializer(),
                LinkOption.serializer(),
                LinkIntent.serializer(),
                FileEntry.serializer(),
                Preview.serializer(),
                ChunkRef.serializer(),
                MissingChunks.serializer(),
                MissingUnits.serializer(),
            ).map { it.descriptor }
        for (descriptor in descriptors) {
            val labels =
                (0 until descriptor.elementsCount).map { i ->
                    descriptor.getElementAnnotations(i).filterIsInstance<CborLabel>().singleOrNull()?.label
                        ?: error("${descriptor.serialName}.${descriptor.getElementName(i)} has no @CborLabel")
                }
            // Declaration order is encoding order: ascending labels keep the map canonical.
            assertEquals(labels.sorted(), labels, "labels of ${descriptor.serialName} must ascend")
            assertEquals(labels.toSet().size, labels.size, "labels of ${descriptor.serialName} must be unique")
        }
    }

    @Test
    fun chunkHeadersEncodeToTheGoldenBytes() {
        for (vector in GoldenVectors.chunkHeaders) {
            assertEquals(vector.hex, vector.header.encode().hex(), vector.name)
            assertEquals(vector.header, ChunkHeader.decode(vector.hex.unhex()), vector.name)
            assertEquals(ChunkHeader.SIZE, vector.hex.length / 2)
        }
    }

    @Test
    fun bundlePayloadsEncodeToTheGoldenBytes() {
        for (vector in GoldenVectors.bundles) {
            assertEquals(vector.hex, BundleIndex.encode(vector.bundle, vector.data).hex(), vector.name)
            val decoded = BundleIndex.decode(vector.hex.unhex(), vector.bundle)
            assertEquals(vector.bundle.entries, decoded.entries)
        }
    }

    @Test
    fun framesEncodeToTheGoldenBytes() {
        for (vector in GoldenVectors.frames) {
            val payload = vector.payloadHex.unhex()
            assertEquals(vector.hex, FrameCodec.encode(vector.type, payload).hex(), vector.name)
            assertEquals(Frame(vector.type, payload), FrameCodec.decode(vector.hex.unhex()), vector.name)
        }
    }

    @Test
    fun allEncodingsListsEveryVector() {
        val all = GoldenVectors.allEncodings()
        val expected =
            GoldenVectors.controlMessages.size + GoldenVectors.chunkHeaders.size + GoldenVectors.bundles.size + GoldenVectors.frames.size
        assertEquals(expected, all.size)
        assertTrue(all.all { it.second.isNotEmpty() })
    }
}
