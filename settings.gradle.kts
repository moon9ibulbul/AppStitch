pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Versi plugin dideklarasikan di sini
        id("com.android.application") version "8.5.2"
        id("com.chaquo.python") version "15.0.1"
        kotlin("android") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StitchApp"
include(":app")
