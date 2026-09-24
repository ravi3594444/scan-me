plugins {
    alias(libs.plugins.drop.jvm.library)
}

// The app-layer adapters every platform shares (WP7ef, moved from platform/desktop-common): the engine's ResumeStore
// over core/data, the resume plan beside the partial files, and the 24 h sweep of partial files. Plain JVM with no
// desktop or Android API, so platform/desktop-common and platform/android both depend on it.
dependencies {
    api(project(":core:data"))
    api(project(":core:transfer"))
    implementation(libs.kotlinx.coroutines.core)
    // core/data declares the SQLite JDBC driver compileOnly (it must stay out of the APK); the tests bring it.
    testImplementation(libs.sqldelight.sqlite.driver)
}
