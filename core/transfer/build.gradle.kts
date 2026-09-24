plugins {
    alias(libs.plugins.drop.kmp.core)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(project(":core:crypto"))
            api(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            // XXH3-128 per-chunk hash (decision 6): pure JVM, also works on Android.
            implementation(libs.zero.allocation.hashing)
        }
    }
}

// The memory budget test (architecture §15: at most 120 MB extra for a transfer) runs in its own JVM under a fixed,
// small heap, so an over-budget engine fails with OutOfMemoryError instead of passing inside the 1 GiB test heap.
val memoryBudgetTest = "*MemoryBudgetTest"

tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(memoryBudgetTest) }
}

val jvmMemoryTest =
    tasks.register<Test>("jvmMemoryTest") {
        description = "Runs the transfer memory budget test under a fixed heap (architecture §15)."
        group = "verification"
        val jvmTest = tasks.named<Test>("jvmTest").get()
        testClassesDirs = jvmTest.testClassesDirs
        classpath = jvmTest.classpath
        maxHeapSize = "320m"
        filter { includeTestsMatching(memoryBudgetTest) }
    }

tasks.named("check") { dependsOn(jvmMemoryTest) }
