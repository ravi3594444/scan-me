import org.gradle.api.Plugin
import org.gradle.api.Project

/** ktlint for modules that do not use another drop convention plugin (the Compose UI modules). */
class KtlintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureKtlint()
}
