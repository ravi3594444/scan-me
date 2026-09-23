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
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
