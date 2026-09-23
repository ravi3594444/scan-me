plugins {
    `kotlin-dsl`
}

group = "com.constrivo.drop.buildlogic"

// Plugins are compileOnly: the root build puts them on the classpath with `apply false`,
// and the convention plugins apply them by id.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpCore") {
            id = "drop.kmp.core"
            implementationClass = "KmpCoreConventionPlugin"
        }
        register("jvmLibrary") {
            id = "drop.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("ktlint") {
            id = "drop.ktlint"
            implementationClass = "KtlintConventionPlugin"
        }
        register("androidLibrary") {
            id = "drop.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
