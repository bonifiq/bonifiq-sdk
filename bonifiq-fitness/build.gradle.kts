import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
}

description = "BonifiQ SDK Fitness (opcional): passos, treinos e batimentos do Health Connect (Android) e do Apple Saúde (iOS), com o fluxo de conexão pronto em Compose."

kotlin {
    explicitApi()

    iosArm64()
    iosSimulatorArm64()

    // Sem plataforma de saúde no desktop: a JVM responde "não suportado" (apps KMP com alvo desktop e testes).
    jvm()

    android {
        namespace = "br.com.bonifiq.sdk.fitness"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            // FitnessConnect: o fluxo de conexão pronto (permissão, Health Connect, "falta 1 passo").
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.compose.uiTestJunit4)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        androidMain.dependencies {
            implementation(libs.androidx.health.connect)
            implementation(libs.androidx.activity)
            implementation(libs.androidx.startup)
        }
    }
}
