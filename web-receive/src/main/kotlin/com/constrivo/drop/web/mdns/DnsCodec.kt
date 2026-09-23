package com.constrivo.drop.web.mdns

/**
 * DNS wire format (RFC 1035 §4) for the mDNS responder (spec change N15).
 *
 * [decode] reads a whole message: header, questions and the three record sections, following compression pointers.
 * It is written for hostile input: every read is bounds-checked, section counts are checked against the bytes left
 * before anything is allocated, a compression pointer must point before the start of its name and each further pointer
 * before the previous target (so pointer chains always end), labels are 1–63 bytes with the reserved label types
 * refused, and a name may not exceed 255 bytes. Every
 * problem raises [DnsFormatException]. Bytes after the last record are ignored.
 *
 * [encode] compresses repeated names (RFC 1035 §4.1.4), except the NSEC next-name, which is written in full so that
 * decoders that do not expect compression inside NSEC data still read it (RFC 6762 §18.14 allows either).
 */
object DnsCodec {
    private const val HEADER = 12
    private const val MIN_QUESTION = 5
    private const val MIN_RECORD = 11
    private const val MAX_MESSAGE = 0xFFFF

    /**
     * Decodes the message in [bytes] from [offset] for [length] bytes.
     *
     * @throws DnsFormatException for any malformed input.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): DnsMessage {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw DnsFormatException("slice outside the buffer")
        val r = Reader(bytes, offset, minOf(length, MAX_MESSAGE))
        if (r.remaining < HEADER) throw DnsFormatException("message shorter than the 12-byte header")
        val id = r.u16()
        val flags = r.u16()
        val qd = r.u16()
        val an = r.u16()
        val ns = r.u16()
        val ar = r.u16()
        if (qd.toLong() * MIN_QUESTION + (an.toLong() + ns + ar) * MIN_RECORD > r.remaining) {
            throw DnsFormatException("section counts exceed the message length")
        }
        val questions =
            List(qd) {
                val name = r.name()
                val type = r.u16()
                val rawClass = r.u16()
                DnsQuestion(name, type, rawClass and 0x7FFF, rawClass and 0x8000 != 0)
            }
        val answers = List(an) { r.record() }
        val authorities = List(ns) { r.record() }
        val additionals = List(ar) { r.record() }
        return DnsMessage(id, flags, questions, answers, authorities, additionals)
    }

    /**
     * Encodes [message].
     *
     * @throws IllegalArgumentException when a name or record does not fit the wire format (label over 63 bytes, name
     *   over 255 bytes, record data over 65535 bytes, or a message over 65535 bytes).
     */
    fun encode(message: DnsMessage): ByteArray {
        val w = Writer()
        w.u16(message.id)
        w.u16(message.flags)
        w.u16(message.questions.size)
        w.u16(message.answers.size)
        w.u16(message.authorities.size)
        w.u16(message.additionals.size)
        for (q in message.questions) {
            w.name(q.name, compress = true)
            w.u16(q.type)
            w.u16(q.qclass or (if (q.unicastResponse) 0x8000 else 0))
        }
        for (rec in message.answers + message.authorities + message.additionals) w.record(rec)
        require(w.size <= MAX_MESSAGE) { "message longer than 65535 bytes" }
        return w.toByteArray()
    }

    private class Reader(
        private val b: ByteArray,
        private val start: Int,
        length: Int,
    ) {
        private val end = start + length
        private var pos = start

        val remaining: Int get() = end - pos

        fun u16(): Int {
            need(2)
            val v = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun u32(): Long {
            need(4)
            var v = 0L
            repeat(4) { v = (v shl 8) or (b[pos++].toLong() and 0xFF) }
            return v
        }

        fun name(): DnsName = nameAt(pos, limit = end).also { pos = it.second }.first

        /**
         * Reads the name at [at], which must end (label bytes or the first pointer) before [limit]; returns the name
         * and the position after it in the original byte stream (after the first pointer when compressed).
         */
        fun nameAt(
            at: Int,
            limit: Int,
        ): Pair<DnsName, Int> {
            val labels = ArrayList<String>()
            var p = at
            var resume = -1
            var wire = 1
            var boundary = limit
            // Each pointer must target data before the previous target (before the name itself at first), so the
            // targets strictly decrease and every chain ends, whatever the labels in between.
            var pointerLimit = at
            while (true) {
                if (p >= boundary) throw DnsFormatException("name runs past its section")
                val len = b[p].toInt() and 0xFF
                when (len and 0xC0) {
                    0x00 -> {
                        if (len == 0) {
                            p++
                            break
                        }
                        if (p + 1 + len > boundary) throw DnsFormatException("label runs past its section")
                        wire += 1 + len
                        if (wire > DnsName.MAX_NAME_BYTES) throw DnsFormatException("name longer than 255 bytes")
                        labels += String(b, p + 1, len, Charsets.UTF_8)
                        p += 1 + len
                    }

                    0xC0 -> {
                        if (p + 1 >= boundary) throw DnsFormatException("truncated compression pointer")
                        val target = start + (((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF))
                        if (target >= pointerLimit) throw DnsFormatException("compression pointer does not point backwards")
                        if (resume < 0) resume = p + 2
                        p = target
                        pointerLimit = target
                        // Pointed-to labels may lie anywhere earlier in the message, even for a name inside NSEC data.
                        boundary = end
                    }

                    else -> {
                        throw DnsFormatException("reserved label type 0x${(len and 0xC0).toString(16)}")
                    }
                }
            }
            return DnsName.decoded(labels) to (if (resume >= 0) resume else p)
        }

        fun record(): DnsRecord {
            val name = name()
            val type = u16()
            val rawClass = u16()
            val ttl = u32()
            val rdLength = u16()
            need(rdLength)
            val dataStart = pos
            val dataEnd = pos + rdLength
            val data =
                when (type) {
                    DnsType.A -> {
                        if (rdLength != 4) throw DnsFormatException("A record data must be 4 bytes, got $rdLength")
                        DnsRData.A(b.copyOfRange(dataStart, dataEnd))
                    }

                    DnsType.AAAA -> {
                        if (rdLength != 16) throw DnsFormatException("AAAA record data must be 16 bytes, got $rdLength")
                        DnsRData.Aaaa(b.copyOfRange(dataStart, dataEnd))
                    }

                    DnsType.NSEC -> {
                        nsec(dataStart, dataEnd)
                    }

                    else -> {
                        DnsRData.Raw(b.copyOfRange(dataStart, dataEnd))
                    }
                }
            pos = dataEnd
            return if (type == DnsType.OPT) {
                DnsRecord(name, type, rawClass, false, ttl, data)
            } else {
                DnsRecord(name, type, rawClass and 0x7FFF, rawClass and 0x8000 != 0, ttl, data)
            }
        }

        private fun nsec(
            dataStart: Int,
            dataEnd: Int,
        ): DnsRData.Nsec {
            val (next, afterName) = nameAt(dataStart, limit = dataEnd)
            if (afterName > dataEnd) throw DnsFormatException("NSEC next name runs past the record data")
            val types = HashSet<Int>()
            var p = afterName
            var lastWindow = -1
            while (p < dataEnd) {
                if (p + 2 > dataEnd) throw DnsFormatException("truncated NSEC type bitmap")
                val window = b[p].toInt() and 0xFF
                val len = b[p + 1].toInt() and 0xFF
                if (window <= lastWindow) throw DnsFormatException("NSEC windows out of order")
                if (len !in 1..32) throw DnsFormatException("NSEC bitmap length must be 1..32")
                if (p + 2 + len > dataEnd) throw DnsFormatException("NSEC bitmap runs past the record data")
                for (i in 0 until len) {
                    val bits = b[p + 2 + i].toInt() and 0xFF
                    for (bit in 0 until 8) if (bits and (0x80 ushr bit) != 0) types += window * 256 + i * 8 + bit
                }
                lastWindow = window
                p += 2 + len
            }
            return DnsRData.Nsec(next, types)
        }

        private fun need(n: Int) {
            if (n > end - pos) throw DnsFormatException("message truncated")
        }
    }

    private class Writer {
        private var data = ByteArray(512)
        var size = 0
            private set
        private val offsets = HashMap<String, Int>()

        fun u16(v: Int) {
            ensure(2)
            data[size++] = (v ushr 8).toByte()
            data[size++] = v.toByte()
        }

        fun u32(v: Long) {
            ensure(4)
            for (shift in 24 downTo 0 step 8) data[size++] = (v ushr shift).toByte()
        }

        fun bytes(v: ByteArray) {
            ensure(v.size)
            v.copyInto(data, size)
            size += v.size
        }

        fun name(
            name: DnsName,
            compress: Boolean,
        ) {
            val encoded = name.labels.map { it.toByteArray(Charsets.UTF_8) }
            require(encoded.all { it.size in 1..DnsName.MAX_LABEL_BYTES }) { "label must be 1..63 bytes" }
            require(encoded.sumOf { it.size + 1 } + 1 <= DnsName.MAX_NAME_BYTES) { "name longer than 255 bytes" }
            for (i in encoded.indices) {
                val key = name.labels.subList(i, name.labels.size).joinToString(".") { lowerAscii(it) }
                if (compress) {
                    val target = offsets[key]
                    if (target != null) {
                        u16(0xC000 or target)
                        return
                    }
                    if (size <= 0x3FFF) offsets[key] = size
                }
                ensure(1)
                data[size++] = encoded[i].size.toByte()
                bytes(encoded[i])
            }
            ensure(1)
            data[size++] = 0
        }

        fun record(rec: DnsRecord) {
            name(rec.name, compress = true)
            u16(rec.type)
            u16(rec.rclass or (if (rec.cacheFlush) 0x8000 else 0))
            u32(rec.ttl)
            val lengthAt = size
            u16(0)
            val dataStart = size
            when (val d = rec.data) {
                is DnsRData.A -> {
                    bytes(d.address)
                }

                is DnsRData.Aaaa -> {
                    bytes(d.address)
                }

                is DnsRData.Raw -> {
                    bytes(d.bytes)
                }

                is DnsRData.Nsec -> {
                    name(d.nextName, compress = false)
                    val byWindow = d.types.groupBy { it ushr 8 }.toSortedMap()
                    for ((window, types) in byWindow) {
                        val bitmap = ByteArray(types.maxOf { (it and 0xFF) / 8 } + 1)
                        for (t in types) {
                            val low = t and 0xFF
                            bitmap[low / 8] = (bitmap[low / 8].toInt() or (0x80 ushr (low % 8))).toByte()
                        }
                        ensure(2)
                        data[size++] = window.toByte()
                        data[size++] = bitmap.size.toByte()
                        bytes(bitmap)
                    }
                }
            }
            val rdLength = size - dataStart
            require(rdLength <= 0xFFFF) { "record data longer than 65535 bytes" }
            data[lengthAt] = (rdLength ushr 8).toByte()
            data[lengthAt + 1] = rdLength.toByte()
        }

        fun toByteArray(): ByteArray = data.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }

        private fun lowerAscii(s: String): String = buildString(s.length) { s.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
    }
}
