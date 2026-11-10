import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

val sdkVersion = libs.versions.bonifiq.sdk.get()

subprojects {
    group = "br.com.bonifiq"
    version = sdkVersion

    // Publicação no Maven Central (Central Portal). No CI, o token e a chave PGP chegam por variáveis
    // ORG_GRADLE_PROJECT_mavenCentralUsername/Password e ORG_GRADLE_PROJECT_signingInMemoryKey/KeyPassword.
    // Sem a chave (ex.: publishToMavenLocal na máquina do dev), publica sem assinar.
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
            publishToMavenCentral()
            if (findProperty("signingInMemoryKey") != null) signAllPublications()
            coordinates("br.com.bonifiq", project.name, sdkVersion)
            pom {
                name.set(
                    when (project.name) {
                        "bonifiq-ui" -> "BonifiQ SDK UI"
                        "bonifiq-fitness" -> "BonifiQ SDK Fitness"
                        "bonifiq-push" -> "BonifiQ SDK Push"
                        else -> "BonifiQ SDK Core"
                    },
                )
                description.set(provider { project.description ?: "" })
                inceptionYear.set("2026")
                url.set("https://github.com/bonifiq/bonifiq-sdk")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("bonifiq")
                        name.set("BonifiQ")
                        url.set("https://www.bonifiq.com.br")
                        organization.set("Bonifiq Desenvolvimento e Licenciamento LTDA")
                        organizationUrl.set("https://www.bonifiq.com.br")
                    }
                }
                scm {
                    url.set("https://github.com/bonifiq/bonifiq-sdk")
                    connection.set("scm:git:git://github.com/bonifiq/bonifiq-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/bonifiq/bonifiq-sdk.git")
                }
            }
        }
    }
}
