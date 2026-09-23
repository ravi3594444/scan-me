plugins {
    alias(libs.plugins.drop.jvm.library)
    application
}

application {
    mainClass.set("com.constrivo.drop.tools.fuzz.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx1g")
}

// The golden vectors of core/protocol are the fuzzers' seed corpus. They use only the public protocol API, so the
// same file compiles here and in core/protocol's commonTest; there is one copy of the bytes.
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(rootProject.file("core/protocol/src/commonTest/kotlin/com/constrivo/drop/core/protocol/golden"))
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    // The smoke run is sized for about ten seconds; -Pdrop.nightly=true (nightly workflow) runs for minutes.
    maxHeapSize = "1g"
    val seed = providers.gradleProperty("drop.fuzz.seed")
    if (seed.isPresent) systemProperty("drop.fuzz.seed", seed.get())
}
