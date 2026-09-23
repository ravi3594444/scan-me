plugins {
    alias(libs.plugins.drop.android.library)
}

android {
    namespace = "com.constrivo.drop.platform.android"
}

dependencies {
    api(project(":core:discovery"))
    api(project(":core:ladder"))
    api(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
}
