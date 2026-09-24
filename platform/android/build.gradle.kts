plugins {
    alias(libs.plugins.drop.android.library)
}

android {
    namespace = "com.constrivo.drop.platform.android"

    defaultConfig {
        // BouncyCastle's lightweight curve classes are referenced directly (no JCA provider is registered), so R8
        // keeps only what the fallback path uses; see consumer-rules.pro.
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        // The library is part of the release gate: a lint error fails the build.
        abortOnError = true
        checkDependencies = false
    }
}

dependencies {
    api(project(":core:discovery"))
    api(project(":core:protocol"))
    api(project(":core:ladder"))
    api(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // Pure-Java X25519 / Ed25519 (RFC 7748 / RFC 8032) and ChaCha20-Poly1305 for devices whose platform JCA lacks them
    // (decision 7, N11). Only the lightweight API is used; see AndroidCryptoProvider for the APK-size budget.
    implementation(libs.bouncycastle.prov)

    // The Bluetooth channels are tested under the real session handshake and transfer engine.
    testImplementation(project(":core:transfer"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // WP7c/d: the Wi-Fi Direct, hotspot and LAN links hand the engine TCP channels (TcpDataChannel, TcpListener) and
    // implement its TcpSocketFactory (NetworkBoundSocketFactory), so core/transfer is part of this module's API.
    api(project(":core:transfer"))
}
