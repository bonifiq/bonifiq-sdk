import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

description = "BonifiQ SDK Push (opcional): notificações push do programa de fidelidade pelo Firebase Cloud Messaging, com o aparelho registrado no login e removido no logout."

kotlin {
    explicitApi()

    iosArm64()
    iosSimulatorArm64()

    // Sem push no desktop: a JVM não registra nada (apps KMP com alvo desktop e testes).
    jvm()

    android {
        namespace = "br.com.bonifiq.sdk.push"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.bonifiqCore)
        }
        androidMain.dependencies {
            // O app configura o Firebase (google-services.json e o plugin google-services); o SDK só usa o FCM.
            api(libs.firebase.messaging)
            implementation(libs.androidx.core)
            implementation(libs.androidx.activity)
            implementation(libs.androidx.startup)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
