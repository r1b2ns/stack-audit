pluginManagement {
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
    ":stackaudit-ui",
    ":stackaudit-apple",
    ":stackaudit-noop",
)

include(":sample-android")
project(":sample-android").projectDir = file("samples/android")
