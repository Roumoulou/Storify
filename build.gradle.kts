import java.util.Properties

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
        }
    }

    repositories {
        // Dépôt Repsy en veille (C-18) : configuré seulement quand ses identifiants sont présents,
        // pour qu'un build ordinaire ne s'encombre pas d'un avertissement tant que la publication dort.
        val globalPropsFile = file("S:/18/global.properties")
        if (globalPropsFile.exists()) {
            maven {
                name = "Repsy"
                url = uri("https://repo.repsy.io/roumoulou/maven")

                credentials {
                    val globalProps = Properties()
                    globalPropsFile.inputStream().use { globalProps.load(it) }

                    username = globalProps.getProperty("respy.io.username", "UTILISATEUR_INCONNU")
                    val bwsUuid = globalProps.getProperty("respy.bws.uuid", "UUID_INCONNU")
                    val bwsExePath = globalProps.getProperty("tools.bws.path")

                    // Le token vit dans Bitwarden Secrets (bws), lu à la volée plutôt qu'écrit en clair.
                    password = try {
                        val execResult = providers.exec {
                            commandLine(bwsExePath, "secret", "get", bwsUuid)
                            isIgnoreExitValue = true // bws absent ou en échec ne casse pas la configuration
                        }
                        val output = execResult.standardOutput.asText.get()
                        val match = "\"value\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(output)
                        match?.groupValues?.get(1) ?: "AUCUN_TOKEN_FOURNI"
                    } catch (_: Exception) { "ERREUR_D_EXECUTION" }
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

    jar { from("LICENSE") { rename { "${it}_${project.base.archivesName.get()}" } } }

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
