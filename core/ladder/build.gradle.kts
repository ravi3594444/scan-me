plugins {
    alias(libs.plugins.drop.kmp.core)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(project(":core:discovery"))
            api(project(":core:transfer"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
