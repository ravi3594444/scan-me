plugins {
    alias(libs.plugins.drop.kmp.core)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api` because DeterministicCbor's public signatures take kotlinx-serialization serializers.
            api(libs.kotlinx.serialization.cbor)
        }
    }
}
