pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "codex-pets-kmp"
include(":codex-pets-core")
include(":codex-pets-io")
include(":codex-pets-compose")
include(":codex-pets-host")
include(":codex-pets-apple")
