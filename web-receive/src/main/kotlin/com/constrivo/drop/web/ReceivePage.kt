package com.constrivo.drop.web

import java.security.MessageDigest
import java.util.Base64

/**
 * The static receive page (design §10): one HTML file with its CSS and JavaScript inline, under [MAX_BYTES].
 *
 * The page is served with a Content-Security-Policy that allows exactly its one inline `<style>` and one inline
 * `<script>`, by SHA-256 hash, plus `fetch`/XHR to its own origin; no other script, style, frame or form target can
 * run in it, so a hostile file name can never become markup (the script also only ever writes text).
 */
object ReceivePage {
    /** Size gate of design §10 ("single HTML file under 30 KB"). */
    const val MAX_BYTES = 30 * 1024

    /** Classpath location of the page. */
    const val RESOURCE = "/com/constrivo/drop/web/index.html"

    /** The page bytes (UTF-8). */
    val bytes: ByteArray by lazy {
        val stream = ReceivePage::class.java.getResourceAsStream(RESOURCE) ?: error("missing resource $RESOURCE")
        stream.use { it.readBytes() }.also { check(it.size < MAX_BYTES) { "the page must stay under $MAX_BYTES bytes" } }
    }

    /** The Content-Security-Policy header value for [bytes]. */
    val contentSecurityPolicy: String by lazy {
        val html = bytes.toString(Charsets.UTF_8)
        val script = inlineBlock(html, "script")
        val style = inlineBlock(html, "style")
        listOf(
            "default-src 'none'",
            "script-src 'sha256-${sha256(script)}'",
            "style-src 'sha256-${sha256(style)}'",
            "img-src data:",
            "connect-src 'self'",
            "form-action 'none'",
            "frame-ancestors 'none'",
            "base-uri 'none'",
        ).joinToString("; ")
    }

    /** The text between the only `<tag>` and its `</tag>` in [html]. */
    internal fun inlineBlock(
        html: String,
        tag: String,
    ): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = html.indexOf(open)
        check(start >= 0 && html.indexOf(open, start + 1) < 0) { "the page must contain exactly one <$tag>" }
        val end = html.indexOf(close, start)
        check(end > start) { "unterminated <$tag>" }
        return html.substring(start + open.length, end)
    }

    private fun sha256(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
