package com.constrivo.drop.web

/**
 * What a `Range` request header asks of a file of a given size (RFC 9110 §14). The server supports one range per
 * request: a multi-range or malformed header is ignored and the whole file is served, as §14.2 allows.
 */
sealed interface RangeRequest {
    /** No usable `Range`: serve the whole file with 200. */
    data object Whole : RangeRequest

    /** Serve bytes [first]..[last] (inclusive) with 206 and `Content-Range: bytes first-last/size`. */
    data class Part(
        val first: Long,
        val last: Long,
    ) : RangeRequest {
        val length: Long get() = last - first + 1
    }

    /** No byte of the file is in the range: 416 with an unsatisfied-range `Content-Range` (asterisk, then the size). */
    data object Unsatisfiable : RangeRequest

    companion object {
        /**
         * Interprets [header] (the `Range` value, or null) for a file of [size] bytes. Never throws for bad input:
         * `bytes=first-last`, `bytes=first-` and `bytes=-suffix` are understood (a `last` past the end is clamped,
         * a suffix longer than the file means the whole file); anything else is [Whole]. An empty file is always
         * served whole, since no byte range can describe it.
         */
        fun parse(
            header: String?,
            size: Long,
        ): RangeRequest {
            require(size >= 0) { "size must not be negative" }
            if (header == null || size == 0L) return Whole
            val eq = header.indexOf('=')
            if (eq < 0 || !header.substring(0, eq).trim().equals("bytes", ignoreCase = true)) return Whole
            val spec = header.substring(eq + 1).trim()
            if (spec.isEmpty() || spec.contains(',')) return Whole
            val dash = spec.indexOf('-')
            if (dash < 0) return Whole
            val firstText = spec.substring(0, dash).trim()
            val lastText = spec.substring(dash + 1).trim()
            if (firstText.isEmpty()) {
                // Suffix range: the last N bytes.
                val suffix = digits(lastText) ?: return Whole
                if (suffix == 0L) return Unsatisfiable
                return Part(maxOf(0L, size - suffix), size - 1)
            }
            val first = digits(firstText) ?: return Whole
            val last =
                if (lastText.isEmpty()) {
                    Long.MAX_VALUE
                } else {
                    digits(lastText) ?: return Whole
                }
            if (last < first) return Whole
            if (first >= size) return Unsatisfiable
            return Part(first, minOf(last, size - 1))
        }

        /** Decimal digits only; values too large for a Long saturate (they are past any file's end anyway). */
        private fun digits(text: String): Long? {
            if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
            val trimmed = text.trimStart('0')
            if (trimmed.isEmpty()) return 0L
            if (trimmed.length > MAX_DIGITS) return Long.MAX_VALUE
            return trimmed.toLong()
        }

        /** 18 digits always fit in a Long (its maximum has 19). */
        private const val MAX_DIGITS = 18
    }
}
