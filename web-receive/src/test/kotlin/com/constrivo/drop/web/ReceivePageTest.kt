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
    fun textMeetsWcagAaContrastInBothThemes() {
        val light = tokens(html.substringAfter(":root{").substringBefore("}"))
        val dark = tokens(html.substringAfter("@media (prefers-color-scheme:dark){:root{").substringBefore("}"))
        for ((theme, t) in listOf("light" to light, "dark" to dark)) {
            fun check(
                foreground: String,
                background: String,
                minimum: Double,
            ) {
                val ratio = contrast(t.getValue(foreground), t.getValue(background))
                assertTrue(ratio >= minimum, "$theme: $foreground on $background is ${"%.3f".format(ratio)}:1, needs $minimum:1")
            }
            // Text (1.4.3): body, secondary, accent-coloured links and buttons, and the primary button's label.
            for (text in listOf("text", "muted", "accent-text")) {
                for (background in listOf("bg", "surface", "accent-soft")) check(text, background, 4.5)
            }
            check("on-accent", "primary", 4.5)
            // Non-text (1.4.11): the progress fill on its track, focus rings and glyphs.
            for (background in listOf("bg", "surface", "accent-soft")) check("accent", background, 3.0)
        }
        // Accent-coloured text never uses the fill token, which is 4.50:1 on white in light mode, just under AA.
        assertFalse(Regex("[^-]color:var\\(--accent\\)[;}]").findAll(html).any { !isNonTextRule(html, it.range.first) })
    }

    @Test
    fun buffersAtMost256MibInThePage() {
        // Larger downloads go to the browser's download manager, with the bar fed by the server (`progress`).
        assertTrue(html.contains("const BUFFER_LIMIT = 256 * 1024 * 1024;"))
        assertTrue(html.contains("'progress?dl='"))
        assertTrue(html.contains("'?dl='"))
    }

    @Test
    fun usesRelativeEndpointPaths() {
        assertTrue(html.contains("fetch('files'"))
        assertTrue(html.contains("fetch('progress?dl="))
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

    private fun tokens(block: String): Map<String, String> =
        Regex("--([a-z-]+):(#[0-9A-Fa-f]{6})").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }

    /** Whether the CSS rule around [index] styles an icon, not text (`svg` or `.glyph`). */
    private fun isNonTextRule(
        css: String,
        index: Int,
    ): Boolean {
        val selector = css.substring(css.lastIndexOf('}', index) + 1, css.lastIndexOf('{', index))
        return selector.contains("svg") || selector.contains(".glyph")
    }

    /** WCAG 2 contrast ratio of two `#RRGGBB` colours. */
    private fun contrast(
        a: String,
        b: String,
    ): Double {
        fun channel(hex: String) = (hex.toInt(16) / 255.0).let { c -> if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }

        fun luminance(color: String): Double {
            val h = color.removePrefix("#")
            return 0.2126 * channel(h.substring(0, 2)) + 0.7152 * channel(h.substring(2, 4)) + 0.0722 * channel(h.substring(4, 6))
        }
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun inlineBlockNeedsExactlyOneBlock() {
        assertEquals("a", ReceivePage.inlineBlock("<p><script>a</script>", "script"))
        assertFailsWith<IllegalStateException> { ReceivePage.inlineBlock("<script>a</script><script>b</script>", "script") }
        assertFailsWith<IllegalStateException> { ReceivePage.inlineBlock("<p>", "script") }
        assertFailsWith<IllegalStateException> { ReceivePage.inlineBlock("<script>a", "script") }
    }
}
