import java.time.LocalDate
import java.time.ZoneOffset

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
    val nightly = providers.gradleProperty("drop.nightly").orNull == "true"
    // The seed is a system property, so it is a task input: a new nightly seed is a new run, never a cache hit.
    // Without -Pdrop.fuzz.seed the nightly seed comes from the UTC date and is printed in the report for replay.
    val seed =
        providers.gradleProperty("drop.fuzz.seed").orNull
            ?: if (nightly) (LocalDate.now(ZoneOffset.UTC).toEpochDay() * 7919).toString() else null
    if (seed != null) systemProperty("drop.fuzz.seed", seed)
    if (nightly) {
        // A nightly campaign must run every time, even when code and seed match an earlier run (a re-run of the job).
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
