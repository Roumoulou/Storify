@file:Suppress("AvoidDuplicateDependencies")

import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("java-library")
    id("maven-publish")
}

val targetJavaVersion = libs.versions.java.get().toInt()
// La variante Java 8 (C-65) : la cible la plus basse qu'un mod Fabric sert, Minecraft 1.16.5.
val jdk8JavaVersion = 8
val toolchains = extensions.getByType<JavaToolchainService>()

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
    // json5 n'est plus une dépendance : kotlin-json5 0.8.0 est copiée dans les sources, sous internal\json5 (C-65).
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.datetime)
    implementation(libs.tomlkt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    // ── Test : JUnit 6 ───────────────────────────────────────────────────
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // ── Test : Logging ───────────────────────────────────────────────────
    testRuntimeOnly(libs.slf4j.simple)

    // CBOR ne sert plus qu'aux tests, pour la démo et la comparaison du benchmark (C-29) : la lib copie par l'arbre JSON.
    testImplementation(libs.kotlinx.serialization.cbor)
}

// ── La variante Java 8 (C-65) ─────────────────────────────────────────
// Les mêmes sources, compilées une seconde fois en cible 1.8. Le jeu de sources `jdk8` lit une copie de src/main/kotlin
// (une tâche Sync : l'IDE ne voit pas deux racines identiques), compilée avec -Xjdk-release=1.8, qui refuse à la
// compilation toute API de Java 9 et plus. Un fichier qu'une variante écrirait autrement irait dans src/jdk8/kotlin,
// posé par-dessus la copie au même chemin relatif, un fichier par différence ; il n'y en a aucun aujourd'hui.
val syncJdk8Sources = tasks.register<Sync>("syncJdk8Sources") {
    description = "Copie src/main/kotlin dans build/jdk8/kotlin, les sources de la variante Java 8 (C-65)."
    from("src/main/kotlin")
    into(layout.buildDirectory.dir("jdk8/kotlin"))
}
val jdk8: SourceSet = sourceSets.create("jdk8")
kotlin.sourceSets.named("jdk8") { kotlin.setSrcDirs(listOf(syncJdk8Sources)) }

configurations {
    named("jdk8Api") { extendsFrom(getByName("api")) }
    named("jdk8Implementation") { extendsFrom(getByName("implementation")) }
    named("jdk8CompileOnly") { extendsFrom(getByName("compileOnly")) }
    named("jdk8RuntimeOnly") { extendsFrom(getByName("runtimeOnly")) }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
    // La variante Java 8 est publiée sous la même coordonnée que la 25, avec la capability du composant : Gradle choisit
    // alors par org.gradle.jvm.version, 8 ou 25, selon la cible du consommateur. Son jar porte le classifier jdk8, ses
    // sources jdk8-sources. Un consommateur Maven, qui ne lit que le POM, reçoit la variante 25, celle de main.
    registerFeature("jdk8") {
        usingSourceSet(jdk8)
        capability(project.group.toString(), project.base.archivesName.get(), project.version.toString())
        withSourcesJar()
    }
}

// Le plugin Kotlin pose ces deux attributs sur les variantes de main ; la variante jdk8 les porte aussi, pour que les
// deux ne diffèrent que par org.gradle.jvm.version.
listOf("jdk8ApiElements", "jdk8RuntimeElements").forEach { name ->
    configurations.named(name) {
        attributes {
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }
}

// ── L'essai de fumée de la variante Java 8 (C-65) ─────────────────────
// Un jeu de sources compilé en cible 1.8 contre le jar jdk8, comme un consommateur, et lancé par smokeJdk8Run sur une
// toolchain 8 avec les dépendances de la lib aux versions du catalogue, celles que fabric-language-kotlin 1.14.2 fournit.
// Les tests JUnit ne tournent pas sur Java 8 (JUnit 6 exige Java 17) et -Xjdk-release garde l'API, pas le comportement :
// sans cet essai, rien ne tiendrait la promesse Java 8 d'une version à l'autre.
val smokeJdk8: SourceSet = sourceSets.create("smokeJdk8")
configurations.named("smokeJdk8Implementation") { extendsFrom(configurations.getByName("jdk8Implementation")) }
dependencies {
    "smokeJdk8Implementation"(files(tasks.named("jdk8Jar")))
    "smokeJdk8RuntimeOnly"(libs.slf4j.simple)
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
                url = "https://github.com/Roumoulou/Storify"

                licenses {
                    license {
                        name = "GNU Lesser General Public License v3.0 only"
                        url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
                        distribution = "repo"
                        comments = "SPDX-License-Identifier: LGPL-3.0-only"
                    }
                }

                developers {
                    developer {
                        id = "roumoulou"
                        name = "Roumoulou"
                        url = "https://github.com/Roumoulou"
                    }
                }

                scm {
                    url = "https://github.com/Roumoulou/Storify"
                    connection = "scm:git:https://github.com/Roumoulou/Storify.git"
                    developerConnection = "scm:git:https://github.com/Roumoulou/Storify.git"
                }
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

    // Les tâches de la variante Java 8 et de son essai de fumée visent 8, les autres 25. `release` fixe la cible de javac
    // et l'attribut org.gradle.jvm.version de la variante publiée : compileJdk8Java n'a aucune source, l'attribut vient de là.
    val jdk8JavaCompileTasks = setOf("compileJdk8Java", "compileSmokeJdk8Java")
    val jdk8KotlinCompileTasks = setOf("compileJdk8Kotlin", "compileSmokeJdk8Kotlin")

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(if (name in jdk8JavaCompileTasks) jdk8JavaVersion else targetJavaVersion)
    }

    withType<KotlinCompile>().matching { it.name in jdk8KotlinCompileTasks }.configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjdk-release=1.8")
        }
    }

    withType<JavaExec>().configureEach { standardInput = System.`in` }

    // Les textes de licence entrent dans les quatre jars (les deux variantes, classes et sources), suffixés du nom de
    // l'artefact pour ne pas entrer en collision dans un jar-in-jar : LICENSE et LICENSE.GPL (C-27), LICENSE.json5 et NOTICE
    // pour la copie de kotlin-json5 (C-65). Le nom se capture à la configuration : la closure de rename s'exécute au run
    // de la tâche, où toucher project est déprécié (erreur en Gradle 10).
    val archivesSuffix = project.base.archivesName.get()
    val licenseFiles = listOf("LICENSE", "LICENSE.GPL", "LICENSE.json5", "NOTICE")

    withType<Jar>().configureEach {
        from(licenseFiles) { rename { "${it}_$archivesSuffix" } }
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

    // ── Les preuves de C-65, sur check ────────────────────────────────

    // Chaque classe du jar principal en bytecode 69 (Java 25), chaque classe du jar jdk8 en 52 (Java 8) : la cible de
    // chaque variante, mesurée sur ce qui est publié et non déduite des options.
    val checkBytecode = register("checkBytecode") {
        description = "Vérifie le bytecode des deux jars : 69 (Java 25) dans le jar principal, 52 (Java 8) dans le jar jdk8 (C-65)."
        group = "verification"
        val mainJar = jar.flatMap { it.archiveFile }
        val jdk8Jar = named<Jar>("jdk8Jar").flatMap { it.archiveFile }
        inputs.files(mainJar, jdk8Jar)
        doLast {
            fun verify(jarFile: File, expected: Int) {
                var count = 0
                val wrong = mutableListOf<String>()
                ZipFile(jarFile).use { zip ->
                    for (entry in zip.entries()) {
                        if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/versions/")) continue
                        val head = zip.getInputStream(entry).use { it.readNBytes(8) }
                        val major = ((head[6].toInt() and 0xff) shl 8) or (head[7].toInt() and 0xff)
                        count++
                        if (major != expected) wrong += "${entry.name} ($major)"
                    }
                }
                if (count == 0) throw GradleException("${jarFile.name} : aucune classe")
                if (wrong.isNotEmpty()) throw GradleException("${jarFile.name} : ${wrong.size} classe(s) hors du bytecode $expected : ${wrong.take(10).joinToString()}")
                println("${jarFile.name} : $count classes, toutes en bytecode $expected")
            }
            verify(mainJar.get().asFile, targetJavaVersion + 44)
            verify(jdk8Jar.get().asFile, jdk8JavaVersion + 44)
        }
    }

    val smokeJdk8Run = register<JavaExec>("smokeJdk8Run") {
        description = "Lance l'essai de fumée de la variante Java 8 sur une toolchain 8 (C-65)."
        group = "verification"
        classpath = smokeJdk8.runtimeClasspath
        mainClass.set("fr.moulou.storify.smoke.SmokeKt")
        javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk8JavaVersion)) })
        args(layout.buildDirectory.dir("tmp/smoke-jdk8").get().asFile.absolutePath)
    }

    check { dependsOn(checkBytecode, smokeJdk8Run) }
}
