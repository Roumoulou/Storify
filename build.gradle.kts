import java.util.Properties
import java.io.FileInputStream

plugins {
//    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("java-library")
    id("maven-publish")
}

val targetJavaVersion = libs.versions.java.get().toInt()

// --- 2. IDENTITÉ DU PROJET ---
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
            // On publie le composant "java" standard (ton code compilé + le pom.xml généré)
            from(components["java"])

            // On récupère les infos de ton gradle.properties
            groupId = project.group.toString()
            artifactId = project.base.archivesName.get()
            version = project.version.toString()

            // Optionnel : si tu veux AUSSI publier ton "shadowJar" (le gros JAR avec tout dedans)
            // artifact(tasks["shadowJar"])
        }
    }

    repositories {
        maven {
            name = "Repsy"
            // Remplace "ton_username" et "ton_repo" par ce que tu auras créé sur repsy.io
            url = uri("https://repo.repsy.io/roumoulou/maven")

            credentials {
                val globalProps = Properties()
                val globalPropsFile = file("S:/18/global.properties")

                if (globalPropsFile.exists()) {
                    globalPropsFile.inputStream().use { stream ->
                        globalProps.load(stream)
                    }
                } else {
                    logger.warn("⚠️ Attention : Le fichier S:/18/global.properties est introuvable !")
                }

                val myRepsyUsername = globalProps.getProperty("respy.io.username", "UTILISATEUR_INCONNU")
                val myBwsUuid = globalProps.getProperty("respy.bws.uuid", "UUID_INCONNU")
                val bwsExePath = globalProps.getProperty("tools.bws.path")

//                val properties = Properties()
//                properties.load(file("S:\\18\\global.properties"))

                username = myRepsyUsername

                password = try {
                    val execResult = providers.exec {
                        commandLine(bwsExePath, "secret", "get", myBwsUuid)

                        // LA LIGNE MAGIQUE : Empêche Gradle de crasher si bws échoue (exit 1)
                        isIgnoreExitValue = true
                    }

                    val output = execResult.standardOutput.asText.get()

                    // La Regex cherche "value", suivi de deux points (avec ou sans espaces),
                    // puis capture tout ce qu'il y a entre les guillemets suivants.
                    val regex = "\"value\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val match = regex.find(output)

                    if (match != null) match.groupValues[1]
                    else "AUCUN_TOKEN_FOURNI"

                } catch (_: Exception) { "ERREUR_D_EXECUTION" }
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

//    shadowJar {
//        // ── Nom du JAR ──────────────────────────────────────────────
//        archiveClassifier.set("all")
//        // → mon-app-1.0.0-all.jar
//
//        // ── Manifest ────────────────────────────────────────────────
//        manifest {
//            attributes(
//                "Implementation-Title" to project.name,
//                "Implementation-Version" to project.version,
//            )
//        }
//
//        mergeServiceFiles()
//
//        // ── Relocate ───────────────────────────────────────────────
//        // Empêche les conflits si quelqu'un utilise ton JAR comme lib
//        // et a déjà zip4j / json-path dans son classpath
//
//        // ── Nettoyage ───────────────────────────────────────────────
//        // Supprime les fichiers inutiles des libs embarquées
//        exclude("META-INF/MANIFEST.MF")  // ceux des libs, pas le tien
//        exclude("META-INF/*.SF")
//        exclude("META-INF/*.DSA")
//        exclude("META-INF/*.RSA")
//        exclude("META-INF/LICENSE*")
//        exclude("META-INF/NOTICE*")
//
//        // ── Stratégie de doublons ───────────────────────────────────
//        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
//
//        // ── Merge des fichiers de service ───────────────────────────
//        mergeServiceFiles()
//        // Quand 2 libs ont un fichier META-INF/services/xxx,
//        // Shadow les fusionne au lieu d'en écraser un
//    }

//    build {
//        dependsOn(shadowJar)
//    }

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