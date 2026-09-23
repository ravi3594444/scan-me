plugins {
    alias(libs.plugins.drop.jvm.library)
}

dependencies {
    // AppIdentity (mDNS host name, display name) and the clock interfaces live in core:discovery. `api`, because the
    // clocks appear in public constructors (ReceiveSession, TransferActivity, MdnsResponder, MdnsHostAnswerer).
    api(project(":core:discovery"))
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host)
}

// Starts the server with sample files for the Playwright test in e2e/ (not part of `check`; see README.md).
tasks.register<JavaExec>("e2eServer") {
    description = "Runs the receive server with sample files for web-receive/e2e/receive.e2e.mjs."
    group = "verification"
    val test = sourceSets["test"]
    classpath = test.runtimeClasspath
    mainClass.set("com.constrivo.drop.web.e2e.E2eServerKt")
    standardInput = System.`in`
}
