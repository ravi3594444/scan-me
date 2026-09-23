plugins {
    alias(libs.plugins.drop.jvm.library)
}

dependencies {
    api(project(":core:protocol"))
}
