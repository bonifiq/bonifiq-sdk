rootProject.name = "bonifiq-sdk"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":bonifiq-core")
include(":bonifiq-ui")
include(":bonifiq-fitness")
include(":bonifiq-push")
// Só geram os XCFrameworks para o Swift Package Manager; não vão ao Maven.
// BonifiQ: core + UI + push. BonifiQFitness: o mesmo + fitness.
include(":bonifiq-ios")
include(":bonifiq-ios-fitness")
