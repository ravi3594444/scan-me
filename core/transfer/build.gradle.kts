plugins {
    alias(libs.plugins.drop.kmp.core)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(project(":core:crypto"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
