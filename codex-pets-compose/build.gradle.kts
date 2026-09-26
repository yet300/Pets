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
        namespace = "com.yet.pets.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget> {
        binaries.framework {
            baseName = "CodexPetsCompose"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":codex-pets-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.compose.ui.test.junit4)
            // Skiko native runtime for the test host OS (desktop JVM tests
            // execute real Skia decode). Test scope only: library consumers
            // (desktop apps) bring their own Skiko runtime.
            implementation(compose.desktop.currentOs)
            // Integration fixture only (io ZIP -> compose): proves the
            // optional io-to-compose flow through public APIs. Production
            // code must never depend on io (verified by source scan + graph).
            implementation(project(":codex-pets-io"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "codex-pets-compose", version.toString())

    pom {
        name = "Codex Pets Compose"
        description = "Compose Multiplatform renderer for Codex-compatible animated pets (Kotlin Multiplatform)"
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
