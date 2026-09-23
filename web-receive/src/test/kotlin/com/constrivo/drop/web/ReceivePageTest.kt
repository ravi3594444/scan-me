package com.constrivo.drop.web

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The static page of design §10: size gate, copy, tokens, and what the CSP relies on. */
class ReceivePageTest {
    private val html = ReceivePage.bytes.toString(Charsets.UTF_8)

    @Test
    fun pageIsUnder30Kilobytes() {
        assertTrue(ReceivePage.bytes.size < 30 * 1024, "index.html is ${ReceivePage.bytes.size} bytes")
    }

    @Test
    fun carriesThePrivacySentenceAndTheCoreCopy() {
        assertTrue(html.contains("No account, no cloud, no tracking. Nothing leaves your phone except the file you chose to send."))
        assertTrue(html.contains("Download all (zip)"))
        assertTrue(html.contains("Send files back"))
        assertTrue(html.contains("' wants to send you '"), "header is \"{name} wants to send you {summary}\"")
        assertTrue(html.contains("Allow"), "tells the user to allow the computer on the phone (N15)")
    }

    @Test
    fun usesTheDesignTokensInBothThemes() {
        val light = listOf("#F7F8FA", "#FFFFFF", "#14171C", "#5F6773", "#2F6BFF", "#E6EDFF")
        val dark = listOf("#0E1116", "#171B22", "#ECEFF3", "#9AA3AF", "#4C82FF", "#1B2540")
        (light + dark).forEach { assertTrue(html.contains(it), "token $it") }
        assertTrue(html.contains("@media (prefers-color-scheme:dark)"))
        assertTrue(html.contains("max-width:560px"))
        assertTrue(html.contains("system-ui"))
        assertTrue(html.contains("<meta name=\"viewport\""))
        assertTrue(html.contains("prefers-reduced-motion"))
    }

    @Test
    fun isOneSelfContainedFileWithoutFrameworks() {
        assertEquals(1, Regex("<script").findAll(html).count())
        assertEquals(1, Regex("<style").findAll(html).count())
        assertFalse(html.contains("<script src"), "no external script")
        assertFalse(html.contains("rel=\"stylesheet\""), "no external stylesheet")
        assertFalse(html.contains("@import"))
        assertFalse(Regex("url\\(").containsMatchIn(html))
        val urls = Regex("https?://[^\"' )]+").findAll(html).map { it.value }.toSet()
        assertEquals(setOf("http://www.w3.org/2000/svg"), urls, "only the SVG namespace")
    }

    @Test
    fun neverTurnsTextIntoMarkup() {
        // The CSP blocks inline handlers anyway; names from the phone are only ever written with textContent.
        assertFalse(Regex("\\son[a-z]+=\"").containsMatchIn(html), "no inline event handlers")
        assertFalse(html.contains("innerHTML"))
        assertFalse(html.contains("insertAdjacentHTML"))
        assertFalse(html.contains("document.write"))
        assertFalse(html.contains("eval("))
        assertFalse(Regex("\\sstyle=\"").containsMatchIn(html), "no style attributes (blocked by the CSP)")
    }

    @Test
    fun usesRelativeEndpointPaths() {
        assertTrue(html.contains("fetch('files'"))
        assertFalse(html.contains("'/files'"))
        assertFalse(html.contains("\"/t/"))
    }

    @Test
    fun cspAllowsExactlyTheInlineScriptAndStyle() {
        val csp = ReceivePage.contentSecurityPolicy

        fun hash(text: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
        val script = html.substringAfter("<script>").substringBefore("</script>")
        val style = html.substringAfter("<style>").substringBefore("</style>")
        assertTrue(csp.contains("script-src 'sha256-${hash(script)}'"), csp)
        assertTrue(csp.contains("style-src 'sha256-${hash(style)}'"), csp)
        assertTrue(csp.startsWith("default-src 'none'"))
        assertTrue(csp.contains("connect-src 'self'"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertFalse(csp.contains("unsafe"))
    }

    @Test
    fun inlineBlockNeedsExactlyOneBlock() {
        assertEquals("a", ReceivePage.inlineBlock("<p><script>a</script>", "script"))
        assertFailsWith<IllegalStateException> { ReceivePage.inlineBlock("<script>a</script><script>b</script>", "script") }
        assertFailsWith<IllegalStateException> { ReceivePage.inlineBlock("<p>", "script") }
        assertFailsWith<IllegalStateException> { ReceivePage.inlineBlock("<script>a", "script") }
    }
}
