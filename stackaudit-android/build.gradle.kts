plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)
    explicitApi()

    android {
        namespace = "io.github.r1b2ns.stackaudit.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    sourceSets {
        androidMain.dependencies {
            api(project(":stackaudit-core"))
            api(libs.okhttp)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
