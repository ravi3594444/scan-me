plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.drop.ktlint)
}

android {
    namespace = "com.constrivo.drop"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.constrivo.drop"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // One debug key for every machine and CI run (not a secret: debug builds only), so a newer debug APK
            // installs over the one already on the phone instead of asking to uninstall it first.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The APK is passed phone to phone and downloaded as a file: dex and native libraries are stored compressed,
        // which halves its size (the install extracts them once).
        dex.useLegacyPackaging = true
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // The unit tests cover the pure parts (permission matrix, share parsing, direct-share ids); Android calls
        // they reach by accident return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":ui:shared"))
    implementation(project(":platform:android"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    // Scan to send (F-B5): CameraX frames decoded by ZXing's QR reader.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
