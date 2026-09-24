pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "drop"

// Shared core: no Android or JVM-desktop imports (enforced by :tools:arch-test).
include(":core:crypto", ":core:protocol", ":core:discovery", ":core:transfer", ":core:ladder", ":core:data")

// Desktop platform layers and the shared desktop/browser pieces.
include(":platform:desktop-win", ":platform:desktop-mac", ":platform:desktop-linux")
include(":platform:desktop-common")
include(":web-receive")
include(":ui:shared", ":ui:desktop")

// Tools.
include(":tools:bench", ":tools:fuzz", ":tools:arch-test")

// Android modules need an SDK. Set ANDROID_HOME or sdk.dir in local.properties;
// without one they are skipped so desktop-only contributors can still build.
val localSdkDir =
    file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
val androidSdk =
    listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), localSdkDir)
        .firstOrNull { !it.isNullOrBlank() }
val hasAndroidSdk = androidSdk != null && file(androidSdk).isDirectory
// Read by modules with an optional Android target (ui/shared) via gradle.extra.
gradle.extensions.extraProperties["drop.hasAndroidSdk"] = hasAndroidSdk
if (hasAndroidSdk) {
    include(":platform:android", ":ui:android")
} else {
    logger.warn("drop: no Android SDK found; skipping :platform:android, :ui:android and the Android target of :ui:shared")
}
