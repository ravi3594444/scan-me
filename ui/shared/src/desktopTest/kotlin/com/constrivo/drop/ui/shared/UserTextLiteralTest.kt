package com.constrivo.drop.ui.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fails when UI code holds a string literal meant for users (F‑I4, decision 9: every string is externalised). A simple
 * source scan: in the composable packages every string literal that contains a letter is reported, after comments are
 * removed. Non-UI packages (models, presenters, fakes, icons' path data, the QR encoder, theme tokens) are not
 * scanned; [TestTags] holds the only letters UI code may use, and it is never shown.
 */
class UserTextLiteralTest {
    private val root = File("src/commonMain/kotlin/com/constrivo/drop/ui/shared")

    /** Packages whose files contain composables. */
    private val uiPackages = listOf("radar", "send", "receive", "dashboard", "onboarding", "components", "text")

    private fun uiFiles(): List<File> {
        val files = uiPackages.flatMap { pkg -> File(root, pkg).walkTopDown().filter { it.extension == "kt" }.toList() }
        return files + File(root, "DropApp.kt")
    }

    /** Removes `//` and `/* */` comments while keeping string contents and line numbers. */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < source.length) {
                        out.append(source[i + 1])
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                }

                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }

                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    for (k in i until end) if (source[k] == '\n') out.append('\n')
                    i = end
                    continue
                }

                else -> {
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    private val literal = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")

    @Test
    fun fI4_noUserFacingLiteralsInComposables() {
        val files = uiFiles()
        assertTrue(files.size >= 15, "the scan found the UI sources (${files.size} files)")
        val violations = ArrayList<String>()
        for (file in files) {
            stripComments(file.readText()).lines().forEachIndexed { index, line ->
                for (m in literal.findAll(line)) {
                    val content = m.value.substring(1, m.value.length - 1)
                    // Template expressions ("$name") are code, not text; only the literal parts count.
                    val text = content.replace(Regex("\\$\\{[^}]*}|\\$[A-Za-z_][A-Za-z0-9_]*"), "")
                    if (text.any { it.isLetter() }) violations += "${file.name}:${index + 1}: ${m.value}"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "user-facing text must come from composeResources/values/strings.xml:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun theScanCatchesALiteral() {
        val sample = "fun Label() { Text(\"Send now\") } // Text(\"in a comment\")\n/* Text(\"block\") */ val tag = \"\${x}\""
        val found = stripComments(sample).lines().flatMap { line -> literal.findAll(line).map { it.value }.toList() }
        assertTrue("\"Send now\"" in found)
        assertTrue(found.none { "comment" in it || "block" in it })
    }
}
