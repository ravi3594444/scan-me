plugins {
    alias(libs.plugins.drop.jvm.library)
}

// The desktop shell's platform layer shared by Windows, macOS and Linux (WP10a): JmDNS discovery, the LAN rung of
// the ladder, app directories, secrets at rest, the database with its SQLite driver, and the DesktopNode composition
// root. OS-specific radios and keychains plug in from :platform:desktop-win, -mac and -linux.
dependencies {
    api(project(":core:ladder"))
    api(project(":core:data"))
    api(project(":web-receive"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jmdns)
    // core/data declares the SQLite JDBC driver compileOnly so it stays out of the APK; desktops bring it (WP6 note).
    implementation(libs.sqldelight.sqlite.driver)
}

tasks.test {
    // The end-to-end test moves a folder of 1,000 files plus two larger files between two nodes in one JVM.
    maxHeapSize = "1g"
}
