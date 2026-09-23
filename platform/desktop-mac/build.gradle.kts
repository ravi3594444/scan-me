plugins {
    alias(libs.plugins.drop.jvm.library)
}

dependencies {
    api(project(":core:ladder"))
    api(project(":core:data"))
}
