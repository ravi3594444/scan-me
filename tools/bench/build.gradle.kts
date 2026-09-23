plugins {
    alias(libs.plugins.drop.jvm.library)
    application
}

application {
    mainClass.set("com.constrivo.drop.tools.bench.MainKt")
}

dependencies {
    implementation(project(":core:transfer"))
    implementation(project(":core:ladder"))
}
