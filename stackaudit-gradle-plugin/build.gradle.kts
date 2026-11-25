plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
}

group = "io.github.r1b2ns"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-SNAPSHOT")

kotlin {
    jvmToolchain(17)
    explicitApi()
}

gradlePlugin {
    website.set("https://github.com/r1b2ns/stack-audit")
    vcsUrl.set("https://github.com/r1b2ns/stack-audit.git")
    plugins {
        create("stackAudit") {
            id = "io.github.r1b2ns.stackaudit"
            implementationClass = "io.github.r1b2ns.stackaudit.gradle.StackAuditPlugin"
            displayName = "StackAudit"
            description = "Adds the StackAudit interceptor to every OkHttpClient built by the app"
            tags.set(listOf("android", "okhttp", "network", "debugging", "inspector"))
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
