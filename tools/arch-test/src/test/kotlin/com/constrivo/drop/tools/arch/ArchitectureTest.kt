package com.constrivo.drop.tools.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import java.io.File
import kotlin.test.Test

/** Rules from docs/handoff.md §3 and docs/architecture.md §3. */
class ArchitectureTest {
    private val forbiddenInCore =
        listOf(
            "android.",
            "androidx.",
            "java.awt.",
            "javax.swing.",
            "com.sun.jna.",
            "org.freedesktop.dbus.",
            "com.constrivo.drop.platform.",
            "com.constrivo.drop.ui.",
            "com.constrivo.drop.web.",
        )

    /** The repository root; tests run with the module directory (tools/arch-test) as working directory. */
    private val root = File(System.getProperty("user.dir")).canonicalFile.parentFile.parentFile.path

    /** Skips local agent worktrees under .claude/, which hold other checkouts of this repository. */
    private val projectScope = Konsist.scopeFromProject().slice { !it.path.startsWith("$root/.claude/") }

    private val coreFiles = Konsist.scopeFromDirectory("core")

    @Test
    fun coreImportsNoPlatformOrUiPackages() {
        coreFiles.imports.assertFalse(additionalMessage = "core/* must stay platform-free (handoff §3)") { import ->
            forbiddenInCore.any { import.name.startsWith(it) }
        }
    }

    @Test
    fun coreFilesLiveInCorePackages() {
        coreFiles.files.assertTrue { it.packagee?.name?.startsWith("com.constrivo.drop.core.") == true }
    }

    @Test
    fun layersPointOneWay() {
        projectScope.assertArchitecture {
            val core = Layer("Core", "com.constrivo.drop.core..")
            val web = Layer("Web receive", "com.constrivo.drop.web..")
            val platform = Layer("Platform", "com.constrivo.drop.platform..")
            val ui = Layer("UI", "com.constrivo.drop.ui..")
            core.dependsOnNothing()
            web.dependsOn(core)
            platform.dependsOn(core, web)
            ui.dependsOn(platform, core, web)
        }
    }
}
