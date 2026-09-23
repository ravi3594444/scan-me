package com.constrivo.drop.web.mdns

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module runs on Android from minSdk 31, where some `java.*` members of the desktop JDK do not exist yet. Android
 * Lint does not check this plain JVM library, so these tests do.
 */
class MdnsResponderAndroidTest {
    @Test
    fun reusePortIsFoundByName() {
        DatagramChannel.open(StandardProtocolFamily.INET).use { ch ->
            val supported = ch.supportedOptions()
            val expected = if (StandardSocketOptions.SO_REUSEPORT in supported) StandardSocketOptions.SO_REUSEPORT else null
            assertEquals(expected, MdnsResponder.reusePortOption(supported))
        }
        assertNull(MdnsResponder.reusePortOption(setOf(StandardSocketOptions.SO_REUSEADDR, StandardSocketOptions.IP_MULTICAST_TTL)))
    }

    @Test
    fun noClassReadsFieldsAndroid12Lacks() {
        val root = File(MdnsResponder::class.java.protectionDomain.codeSource.location.toURI())
        val classes = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
        assertTrue(classes.any { it.name == "MdnsResponder.class" }, "scanning the module's own classes in $root")
        for (file in classes) {
            val used = fieldReferences(file.readBytes()).intersect(FIELDS_AFTER_API_31)
            assertTrue(used.isEmpty(), "${file.relativeTo(root)} reads $used (NoSuchFieldError on Android 12)")
        }
    }

    @Test
    fun theScannerSeesFieldReferences() {
        // This test class itself reads StandardSocketOptions.SO_REUSEPORT (above), so the scanner must report it.
        val bytes = javaClass.getResourceAsStream("MdnsResponderAndroidTest.class")!!.use { it.readBytes() }
        assertTrue("java/net/StandardSocketOptions.SO_REUSEPORT" in fieldReferences(bytes))
    }

    private companion object {
        /** `owner.name` of fields the JDK has but Android only from API 32 or later (api-versions.xml). */
        val FIELDS_AFTER_API_31 = setOf("java/net/StandardSocketOptions.SO_REUSEPORT")

        /** Every field reference (`getstatic`, `getfield` and friends) in a class file, as `owner.name`. */
        fun fieldReferences(classFile: ByteArray): Set<String> {
            val input = DataInputStream(ByteArrayInputStream(classFile))
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val className = IntArray(count)
            val memberName = IntArray(count)
            val fields = ArrayList<Pair<Int, Int>>()
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> {
                        utf8[i] = input.readUTF()
                    }

                    3, 4 -> {
                        input.readInt()
                    }

                    5, 6 -> {
                        input.readLong()
                        i++ // eight-byte constants take two slots
                    }

                    7 -> {
                        className[i] = input.readUnsignedShort()
                    }

                    8, 16, 19, 20 -> {
                        input.readUnsignedShort()
                    }

                    9 -> {
                        fields += input.readUnsignedShort() to input.readUnsignedShort()
                    }

                    10, 11, 17, 18 -> {
                        input.readInt()
                    }

                    12 -> {
                        memberName[i] = input.readUnsignedShort()
                        input.readUnsignedShort()
                    }

                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                    }

                    else -> {
                        error("unknown constant pool tag $tag")
                    }
                }
                i++
            }
            return fields.map { (owner, nameAndType) -> "${utf8[className[owner]]}.${utf8[memberName[nameAndType]]}" }.toSet()
        }
    }
}
