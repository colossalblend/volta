// Volta for Windows: a standalone Gradle build, apart from the Android app in ../app.
// Build it from this folder: `./gradlew run`, `./gradlew packageExe`.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "volta-desktop"
