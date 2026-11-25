pluginManagement {
    includeBuild("stackaudit-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "stack-audit"

include(
    ":stackaudit-core",
    ":stackaudit-ktor",
    ":stackaudit-android",
    ":stackaudit-sqldelight",
    ":stackaudit-settings",
    ":stackaudit-ui",
    ":stackaudit-apple",
    ":stackaudit-cli",
    ":stackaudit-noop",
    ":stackaudit-ktor-noop",
    ":stackaudit-sqldelight-noop",
    ":stackaudit-settings-noop",
)

include(":sample-android")
project(":sample-android").projectDir = file("samples/android")
