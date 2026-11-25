plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.r1b2ns.stackaudit.cli.MainKt")
    applicationName = "stackaudit-cli"
}

dependencies {
    implementation(project(":stackaudit-core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
