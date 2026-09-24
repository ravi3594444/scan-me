plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.drop.ktlint)
}

// The Android target needs an SDK; without one (desktop-only contributors, agent worktrees) only the desktop target builds.
val hasAndroidSdk = gradle.extensions.extraProperties["drop.hasAndroidSdk"] as Boolean
if (hasAndroidSdk) {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
}

kotlin {
    if (hasAndroidSdk) {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "com.constrivo.drop.ui.shared"
            compileSdk = libs.versions.compileSdk.get().toInt()
            minSdk = libs.versions.minSdk.get().toInt()
            // Compose Multiplatform resources (strings, plurals) are packaged as Android resources/assets.
            androidResources.enable = true
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.animation)
            implementation(libs.compose.components.resources)
            // All targets are JVM-based, so commonMain can use the JVM core modules and ZXing directly.
            api(project(":core:discovery"))
            api(project(":core:ladder"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.zxing.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopTest").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.constrivo.drop.ui.shared.resources"
    generateResClass = always
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // `./gradlew :ui:shared:desktopTest -Pdrop.recordScreenshots=true` rewrites the reference PNGs (see README.md).
    systemProperty("drop.recordScreenshots", providers.gradleProperty("drop.recordScreenshots").orElse("false").get())
    // Screenshot tests compare against the checked-in PNGs; re-run them when those change.
    inputs.dir("src/desktopTest/resources/screenshots").optional().withPropertyName("screenshots")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
