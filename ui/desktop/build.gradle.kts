import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.drop.ktlint)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":ui:shared"))
    implementation(project(":platform:desktop-win"))
    implementation(project(":platform:desktop-mac"))
    implementation(project(":platform:desktop-linux"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "com.constrivo.drop.ui.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Drop"
            packageVersion = "0.1.0"
        }
    }
}
