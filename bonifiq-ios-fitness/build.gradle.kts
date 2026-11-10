import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// XCFramework "BonifiQFitness" para apps Swift: o mesmo conteúdo do "BonifiQ" (core + UI + push) mais o módulo Fitness.
// Fica num framework à parte porque referenciar o HealthKit exige capability e textos de privacidade no app; quem não
// usa fitness continua no "BonifiQ". Não é publicado no Maven (apps Kotlin usam br.com.bonifiq:bonifiq-fitness).
kotlin {
    val xcFramework = XCFramework("BonifiQFitness")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BonifiQFitness"
            isStatic = true
            export(projects.bonifiqCore)
            export(projects.bonifiqUi)
            export(projects.bonifiqPush)
            export(projects.bonifiqFitness)
            xcFramework.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(projects.bonifiqCore)
            api(projects.bonifiqUi)
            api(projects.bonifiqPush)
            api(projects.bonifiqFitness)
        }
    }
}
