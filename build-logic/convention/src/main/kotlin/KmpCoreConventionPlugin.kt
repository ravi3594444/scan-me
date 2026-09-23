import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared-core module: Kotlin Multiplatform with a JVM target.
 *
 * Pure logic goes in `commonMain`; JCA, java.nio and file I/O go in `jvmMain`.
 * Android consumes the JVM variant, desktop runs it directly, and an iOS target can be
 * added in Phase 2 without moving code (docs/architecture.md §17).
 */
class KmpCoreConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            extensions.configure<KotlinMultiplatformExtension> {
                jvm {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.fromTarget(version("jvmTarget")))
                        freeCompilerArgs.add("-Xjdk-release=${version("jvmTarget")}")
                    }
                }
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                    optIn.add("kotlin.ExperimentalUnsignedTypes")
                }
                sourceSets.getByName("commonTest").dependencies {
                    implementation(libs.findLibrary("kotlin-test").get())
                    implementation(libs.findLibrary("kotlinx-coroutines-test").get())
                }
            }
            configureTests()
            configureKtlint()
        }
}
