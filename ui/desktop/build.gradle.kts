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
    implementation(project(":platform:desktop-common"))
    implementation(project(":platform:desktop-win"))
    implementation(project(":platform:desktop-mac"))
    implementation(project(":platform:desktop-linux"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.zxing.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Tray, drop target and window logic are tested without a display; the window content through Skia offscreen.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "com.constrivo.drop.ui.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Drop"
            packageVersion = "0.1.0"
            // JmDNS and the JDBC driver need java.naming and java.sql; the tray and file dialogs need java.desktop.
            modules("java.naming", "java.sql", "java.desktop", "jdk.unsupported")
        }
    }
}
