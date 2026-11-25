plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

group = "io.github.r1b2ns"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
    explicitApi()
}

gradlePlugin {
    plugins {
        create("stackAudit") {
            id = "io.github.r1b2ns.stackaudit"
            implementationClass = "io.github.r1b2ns.stackaudit.gradle.StackAuditPlugin"
            displayName = "StackAudit"
            description = "Adds the StackAudit interceptor to every OkHttpClient built by the app"
        }
    }
}

dependencies {
    compileOnly(libs.android.gradle.api)
    implementation(libs.asm)
    testImplementation(kotlin("test"))
    testImplementation(libs.asm.tree)
    testImplementation(libs.okhttp)
}
