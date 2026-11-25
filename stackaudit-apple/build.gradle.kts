import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.skie)
}

kotlin {
    explicitApi()

    val xcframework = XCFramework("StackAuditKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "StackAuditKit"
            isStatic = true
            export(project(":stackaudit-core"))
            export(project(":stackaudit-ui"))
            linkerOpts("-lsqlite3")
            xcframework.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":stackaudit-core"))
            api(project(":stackaudit-ui"))
        }
    }
}
