group = "io.github.r1b2ns.stackaudit.flutter"
version = "0.1.0"

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins.apply("com.android.library")
plugins.apply("kotlin-android")

extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "io.github.r1b2ns.stackaudit.flutter"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDirs("src/main/kotlin")
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>("kotlin") {
    jvmToolchain(17)
}

dependencies {
    "implementation"("io.github.r1b2ns:stackaudit-android:0.4.1")
    "implementation"("io.github.r1b2ns:stackaudit-ui:0.4.1")
}
