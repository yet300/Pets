import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

tasks.register<Exec>("verifySwiftConsumer") {
    dependsOn("linkDebugFrameworkIosSimulatorArm64")
    commandLine(
        "xcrun", "--sdk", "iphonesimulator", "swiftc", "-typecheck",
        "-target", "arm64-apple-ios16.0-simulator",
        "-module-cache-path", layout.buildDirectory.dir("swift-module-cache").get().asFile.absolutePath,
        "-F", layout.buildDirectory.dir("bin/iosSimulatorArm64/debugFramework").get().asFile.absolutePath,
        file("swift-tests/Consumer.swift").absolutePath,
    )
}

group = "com.yet.pets"
version = "0.1.0"

kotlin {
    explicitApi()
    iosArm64()
    iosSimulatorArm64()
    val consumerXCFramework = XCFramework("CodexPets")

    sourceSets {
        commonMain.dependencies {
            api(project(":codex-pets-core"))
            api(project(":codex-pets-io"))
            api(project(":codex-pets-compose"))
            api(project(":codex-pets-host"))
        }
    }

    targets.withType<KotlinNativeTarget> {
        binaries.framework {
            baseName = "CodexPets"
            export(project(":codex-pets-core"))
            export(project(":codex-pets-io"))
            export(project(":codex-pets-compose"))
            export(project(":codex-pets-host"))
            transitiveExport = false
            consumerXCFramework.add(this)
        }
    }
}
