plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("java-library")
    id("maven-publish")
}

val targetJavaVersion = libs.versions.java.get().toInt()

// ── Identité du projet ───────────────────────────────────────────────
version = project.property("mod_version").toString()
group = project.property("maven_group").toString()

base {
    archivesName.set(project.property("archives_base_name") as String)
}

repositories {
    mavenCentral()
}

dependencies {

    // ── Dépendances principales ─────────────────────────────────────
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.datetime)
    implementation(libs.tomlkt)
    implementation(libs.json5)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.slf4j.api)

    // ── Test : JUnit 6 + Kotest + MockK ─────────────────────────────────
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    // ── Test : Logging ───────────────────────────────────────────────────
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly(libs.slf4j.simple)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = project.base.archivesName.get()
            version = project.version.toString()

            pom {
                name = "Storify"
                description = "Kotlin file-backed data and config stores for Minecraft mods and JVM " +
                    "projects: JSON, TOML and JSON5 formats, typed updates, callbacks, validation, atomic saves."
            }
        }
    }

    repositories {
        // Le dépôt Repsy (C-18) : le jeton arrive par la chaîne de secrets (dev-secrets.ps1 -Apply
        // REPSY_MAVEN_TOKEN), en variable d'environnement le temps du terminal qui publie. Sans elle,
        // le dépôt n'est pas configuré : un build ordinaire reste muet et ne peut rien publier par accident.
        val repsyToken = providers.environmentVariable("REPSY_MAVEN_TOKEN").orNull
        if (repsyToken != null) {
            maven {
                name = "Repsy"
                url = uri("https://repo.repsy.io/roumoulou/maven")

                credentials {
                    username = "roumoulou"
                    password = repsyToken
                }
            }
        }
    }
}

tasks {

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

    withType<JavaExec>().configureEach { standardInput = System.`in` }

    jar {
        // Capturé à la configuration : la closure de rename s'exécute au run de la tâche,
        // où toucher project est déprécié (erreur en Gradle 10).
        val archivesSuffix = project.base.archivesName.get()
        from("LICENSE") { rename { "${it}_$archivesSuffix" } }
    }

    test {
        useJUnitPlatform()

        // Affiche les tests dans le terminal
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            showExceptions = true
            showCauses = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
