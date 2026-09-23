plugins {
    alias(libs.plugins.drop.kmp.core)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // @CborLabel, @CborArray, @EncodeDefault and SerialDescriptor(name, original) are still marked experimental.
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core:crypto"))
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
