import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.version(alias: String): String = libs.findVersion(alias).get().requiredVersion

/** ktlint on every Kotlin module; `./gradlew ktlintFormat` fixes most findings. */
internal fun Project.configureKtlint() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    extensions.configure(KtlintExtension::class.java) {
        version.set(this@configureKtlint.version("ktlint"))
        filter {
            exclude { it.file.path.contains("/build/") }
            exclude("**/generated/**")
        }
    }
}

internal fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
        // Long-running suites (fuzz, loopback soak) read this to scale up in the nightly job.
        systemProperty("drop.nightly", providers.gradleProperty("drop.nightly").orElse("false").get())
    }
}
