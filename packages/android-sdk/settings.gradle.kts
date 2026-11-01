pluginManagement {
    plugins {
        id("com.android.library") version "8.3.2"
        id("org.jetbrains.kotlin.android") version "1.9.22"
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "argus-android-sdk"
