import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// XCFramework "BonifiQ" para apps Swift (SwiftUI/UIKit): core, UI e push. O push não traz o Firebase: o app usa o
// Firebase iOS SDK e repassa o token. Não é publicado no Maven (apps Kotlin usam os artefatos br.com.bonifiq:*).
kotlin {
    val xcFramework = XCFramework("BonifiQ")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BonifiQ"
            isStatic = true
            export(projects.bonifiqCore)
            export(projects.bonifiqUi)
            export(projects.bonifiqPush)
            xcFramework.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(projects.bonifiqCore)
            api(projects.bonifiqUi)
            api(projects.bonifiqPush)
        }
    }
}
