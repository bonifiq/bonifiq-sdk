import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
}

description = "BonifiQ SDK UI: hub de fidelidade (widget BonifiQ) e componentes prontos em Compose Multiplatform. Inclui o core."

kotlin {
    explicitApi()

    // Apps iOS nativos (SwiftUI/UIKit) usam o XCFramework "BonifiQ", gerado pelo módulo bonifiq-ios (core + UI + push).
    iosArm64()
    iosSimulatorArm64()

    jvm()

    android {
        namespace = "br.com.bonifiq.sdk.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.bonifiqCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.browser)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}
