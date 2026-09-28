import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
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
        namespace = "com.yet.pets.io"
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
            baseName = "CodexPetsIo"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":codex-pets-core"))
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.okio.fakefilesystem)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "codex-pets-io", version.toString())

    pom {
        name = "Codex Pets IO"
        description = "Filesystem and ZIP package loading for Codex-compatible animated pets (Kotlin Multiplatform)"
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
