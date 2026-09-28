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

rootProject.name = "pets-kmp"
include(":pets-core")
include(":pets-io")
include(":pets-compose")
include(":pets-host")
include(":pets-apple")

include(":example")
include(":example:shared")
include(":example:androidApp")
include(":example:desktopApp")