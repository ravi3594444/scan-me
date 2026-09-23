plugins {
    alias(libs.plugins.drop.jvm.library)
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.test {
    // Konsist parses the whole source tree; re-run when any Kotlin file changes.
    inputs.files(
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    )
}
