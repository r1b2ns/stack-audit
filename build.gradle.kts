import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

val published =
    mapOf(
        "stackaudit-core" to "Event model, encrypted store, secret masking, export and handshake for StackAudit",
        "stackaudit-ktor" to "Ktor client plugin that records HTTP and WebSocket traffic into StackAudit",
        "stackaudit-android" to "Android capture for StackAudit: OkHttp, SharedPreferences, Room and startup wiring",
        "stackaudit-sqldelight" to "SqlDriver decorator that records SQLDelight statements into StackAudit",
        "stackaudit-settings" to "multiplatform-settings decorator that records key-value changes into StackAudit",
        "stackaudit-ui" to "Compose Multiplatform inspector screens for StackAudit",
        "stackaudit-noop" to "No-op StackAudit API for release builds",
        "stackaudit-ktor-noop" to "No-op StackAudit Ktor plugin for release builds",
        "stackaudit-sqldelight-noop" to "No-op StackAudit SqlDriver decorator for release builds",
        "stackaudit-settings-noop" to "No-op StackAudit settings decorator for release builds",
    )

val signing = providers.gradleProperty("signingInMemoryKey").isPresent

subprojects {
    val summary = published[name] ?: return@subprojects
    apply(plugin = "com.vanniktech.maven.publish")
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        if (signing) signAllPublications()
        coordinates(group.toString(), name, version.toString())
        pom {
            name.set(project.name)
            description.set(summary)
            inceptionYear.set("2026")
            url.set("https://github.com/r1b2ns/stack-audit")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("r1b2ns")
                    name.set("r1b2ns")
                    url.set("https://github.com/r1b2ns")
                }
            }
            scm {
                url.set("https://github.com/r1b2ns/stack-audit")
                connection.set("scm:git:git://github.com/r1b2ns/stack-audit.git")
                developerConnection.set("scm:git:ssh://git@github.com/r1b2ns/stack-audit.git")
            }
        }
    }
}
