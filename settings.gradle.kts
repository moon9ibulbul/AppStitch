pluginManagement {
    repositories {
        // ⬅️ WAJIB: repo plugin Chaquopy
        maven { url = uri("https://chaquo.com/maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("com.chaquo.python") version "16.1.0"   // ⬅️ versi yang kamu ingin pakai
        kotlin("android") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ⬅️ WAJIB juga di sini untuk dependency runtime Chaquopy
        maven { url = uri("https://chaquo.com/maven") }
        google()
        mavenCentral()
    }
}

rootProject.name = "StitchApp"
include(":app")
