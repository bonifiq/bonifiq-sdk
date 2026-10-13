import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

description = "BonifiQ SDK Fitness (opcional): passos, treinos e batimentos do Health Connect (Android) e do Apple Saúde (iOS)."

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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.health.connect)
            implementation(libs.androidx.activity)
            implementation(libs.androidx.startup)
        }
    }
}
