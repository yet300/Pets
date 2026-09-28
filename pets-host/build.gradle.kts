import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.yet.pets"
version = "0.1.0"

kotlin {
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    jvm()
    android {
        namespace = "com.yet.pets.host"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withDeviceTest {}
        withJava()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget> {
        binaries.framework {
            baseName = "CodexPetsHost"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.runtime.android)
            implementation(libs.androidx.lifecycle.runtime.compose.android)
            implementation(libs.androidx.lifecycle.viewmodel.android)
            implementation(libs.androidx.savedstate.android)
        }
        commonMain.dependencies {
            implementation(project(":codex-pets-core"))
            implementation(project(":codex-pets-compose"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.ui.test.junit4)
            // Skiko native runtime for the test host OS (desktop JVM tests
            // execute real Compose UI). Test scope only.
            implementation(compose.desktop.currentOs)
        }

        iosTest.dependencies {
            implementation(libs.compose.ui.test)
        }

        named("androidDeviceTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.junit4)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.core)
                implementation(libs.compose.ui.test.junit4)
                // Force Espresso past the 3.5.0 pulled by compose ui-test:
                // 3.5.0 calls hidden InputManager.getInstance(), removed on
                // API 36, which crashes the compose test rule before setContent.
                implementation(libs.androidx.test.espresso.core)
                implementation(libs.androidx.compose.ui.test.manifest)
            }
        }
    }
}

tasks.matching { it.name.startsWith("copyAndroidDeviceTestComposeResources") }.configureEach {
    enabled = false
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "codex-pets-host", version.toString())

    pom {
        name = "Codex Pets Host"
        description = "Cross-platform pet host for Codex-compatible animated pets (Kotlin Multiplatform)"
        inceptionYear = "2026"
        url = "https://github.com/yet300/Pets"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "yet300"
                name = "yet300"
                url = "https://github.com/yet300"
            }
        }
        scm {
            url = "https://github.com/yet300/Pets"
            connection = "scm:git:https://github.com/yet300/Pets.git"
            developerConnection = "scm:git:https://github.com/yet300/Pets.git"
        }
    }
}
