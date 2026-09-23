import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android library (platform/android). AGP 9 compiles Kotlin itself (built-in Kotlin),
 * so no separate Kotlin Android plugin is applied.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                compileSdk = version("compileSdk").toInt()
                defaultConfig {
                    minSdk = version("minSdk").toInt()
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.toVersion(version("jvmTarget"))
                    targetCompatibility = JavaVersion.toVersion(version("jvmTarget"))
                }
                testOptions { unitTests.isReturnDefaultValues = true }
            }
            configureTests()
            configureKtlint()
        }
}
