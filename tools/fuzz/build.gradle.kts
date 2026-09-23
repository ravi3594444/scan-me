plugins {
    alias(libs.plugins.drop.jvm.library)
    application
}

application {
    mainClass.set("com.constrivo.drop.tools.fuzz.MainKt")
}

dependencies {
    implementation(project(":core:protocol"))
}
