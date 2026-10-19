/*
════════════════════════════════════════════════════════════════════════════════
 BUILD SCRIPT — Storify, la bibliothèque des stores de données et de configuration
════════════════════════════════════════════════════════════════════════════════

 Une racine Gradle à un seul projet, Kotlin/JVM, sans Loom : la lib n'est pas un mod,
 elle en est une dépendance. Elle publie `fr.moulou:storify` sur Repsy en deux variantes
 sous la même coordonnée, Java 25 et Java 8 (C-65), que Gradle choisit d'après la cible
 du consommateur. Le banc d'essai, Storibench, la consomme en build composite depuis le
 classeur (`..\Storibench`) et l'éprouve dans un vrai serveur Fabric.

 ┌───────────────────────────────────────────────────────────────────────────┐
 │  1  l'identité, lue dans gradle.properties                                │
 │  2  les dépendances : ce que FLK fournit, ce qu'un mod embarque, les tests│
 │  3  Java et les compilateurs : la toolchain 25, la cible 8 de la variante │
 │  4  la variante Java 8 : la copie des sources, la feature, ses attributs  │
 │  5  les jars : les quatre textes de licence                               │
 │  6  les tests : JUnit 6, sur la variante Java 25                          │
 │  7  les preuves de la variante 8 : le bytecode et l'essai de fumée        │
 │  8  la publication : le POM, Repsy derrière le jeton, le dépôt local      │
 └───────────────────────────────────────────────────────────────────────────┘

 Plugins, aux versions du catalogue libs :
   - kotlin-jvm             le langage ; la même version que fabric-language-kotlin,
                            qui fournit la stdlib et reflect au runtime d'un mod
   - kotlin-serialization   les @Serializable de la lib (StoreMeta) et ceux des tests
   - java-library           les configurations api et implementation, les jars, les
                            variantes publiées
   - maven-publish          la publication, sur Repsy et dans le dépôt Maven local

 Les dépôts vivent dans settings.gradle.kts, et nulle part ici (FAIL_ON_PROJECT_REPOS).
 La doc de la lib est dans Docs\ : architecture.md (le chapitre 14 pour les variantes)
 et chantiers.md (C-65, C-66) ; la recette d'un consommateur, au README, section 4.
════════════════════════════════════════════════════════════════════════════════
*/

import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `maven-publish`
}

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 1 — L'IDENTITÉ
════════════════════════════════════════════════════════════════════════════════

 Lue dans gradle.properties : chaque valeur se monte à UN seul endroit.

 - group          : groupe Maven, clé maven_group (fr.moulou).
 - archivesName   : nom de base des jars et artifactId, clé archives_base_name (storify).
 - version        : clé lib_version. `master` porte la prochaine version en -SNAPSHOT ;
                    une release la perd le temps d'un commit et d'un tag
                    (git-et-versions.md, The Human Readme, chapitre 3).

 Les deux cibles Java du build : celle de la lib, clé `java` du catalogue (25), et celle
 de la variante Java 8 (section 4), 8, la plus basse qu'un mod Fabric sert (1.16.5).
════════════════════════════════════════════════════════════════════════════════
*/
group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("lib_version").get()
base { archivesName = providers.gradleProperty("archives_base_name") }

val targetJavaVersion = libs.versions.java.get().toInt()
val jdk8JavaVersion = 8
val toolchains = extensions.getByType<JavaToolchainService>()

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 2 — LES DÉPENDANCES
════════════════════════════════════════════════════════════════════════════════

 Toutes en `implementation` : le POM les déclare en `runtime`, et celui qui compile
 contre Storify déclare lui-même kotlinx-serialization, dont il annote ses data classes.
 Au runtime d'un mod, trois origines :

 ── CE QUE FABRIC-LANGUAGE-KOTLIN FOURNIT ──────────────────────────────────
 kotlin-stdlib et kotlin-reflect 2.4.21, kotlinx-serialization 1.11.0, kotlinx-datetime
 0.8.0, aux versions de ce catalogue exactement : le plancher d'un mod est FLK >= 1.14.2,
 et son jar n'embarque rien de tout cela (README, section 4).

 ── CE QU'UN MOD EMBARQUE AVEC STORIFY ─────────────────────────────────────
 tomlkt, le format TOML, en bytecode Java 8 depuis toujours : le second jar-in-jar.

 ── CE QUE LE JEU FOURNIT ──────────────────────────────────────────────────
 slf4j-api, depuis Minecraft 1.17.1 ; 1.16.5 ne l'a pas, et c'est le mod qui l'y
 embarque. Ici, la 2.0.20 pour compiler et pour les tests.

 ── JSON5, UNE COPIE ET NON UNE DÉPENDANCE ─────────────────────────────────
 kotlin-json5 0.8.0 (li.songe:json5) est publiée en bytecode Java 11 : la variante
 Java 8 ne pourrait pas l'embarquer. Ses sources sont copiées sous
 src\main\kotlin\fr\moulou\storify\internal\json5, Apache-2.0 (LICENSE.json5, NOTICE), et
 compilées avec le reste dans les deux variantes (C-65) : le POM ne la déclare plus.

 ── LES TESTS ──────────────────────────────────────────────────────────────
 JUnit 6 (la plateforme unifiée, qui exige Java 17 : la suite tourne sur la toolchain 25,
 jamais sur Java 8, que la section 7 éprouve autrement), slf4j-simple pour lire les logs
 de la lib dans le terminal, et CBOR, qui ne sert plus qu'à la démo et au benchmark des
 copies profondes (C-29) : la lib copie par l'arbre JSON.
════════════════════════════════════════════════════════════════════════════════
*/
dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.datetime)
    implementation(libs.tomlkt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotlinx.serialization.cbor)
}

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 3 — JAVA ET LES COMPILATEURS
════════════════════════════════════════════════════════════════════════════════

 - toolchain          : Gradle compile et teste avec un JDK précis, celui de la clé `java`
                        du catalogue (25), déclaré au poste par installations.paths et
                        jamais téléchargé (auto-download=false dans le gradle.properties
                        global). Le JDK 8 de l'essai de fumée (section 7) vient du même
                        réglage.
 - withSourcesJar()   : un jar de sources à côté du jar, pour les IDE et la publication.

 ── RELEASE, PAR TÂCHE ─────────────────────────────────────────────────────
 `release` fixe la cible de javac et, par le plugin Java, l'attribut org.gradle.jvm.version
 de la variante que le jeu de sources publie : 25 pour main et test, 8 pour la variante
 jdk8 et son essai de fumée. Il n'y a aucune source Java : compileJdk8Java reste
 NO-SOURCE, et l'attribut vient de là quand même (mesuré le 2026-10-18).

 ── KOTLIN EN CIBLE 1.8 ────────────────────────────────────────────────────
 Les compilations de la variante 8 reçoivent jvmTarget 1.8 et -Xjdk-release=1.8, qui
 compile contre l'API de Java 8 du JDK 25 (son ct.sym) et refuse à la compilation toute
 API plus récente : readNBytes, la seule que le code appelait, a été refusée ainsi avant
 d'être réécrite (Utf8Bom.kt). Le plugin Kotlin vérifie que jvmTarget et release
 concordent, tâche par tâche. L'encodage UTF-8 est forcé côté Java : sans lui, javac suit
 l'encodage du système, et les accents de ce projet sortent en charabia ailleurs.
════════════════════════════════════════════════════════════════════════════════
*/
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

val jdk8JavaCompileTasks = setOf("compileJdk8Java", "compileSmokeJdk8Java")
val jdk8KotlinCompileTasks = setOf("compileJdk8Kotlin", "compileSmokeJdk8Kotlin")

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = if (name in jdk8JavaCompileTasks) jdk8JavaVersion else targetJavaVersion
}

tasks.withType<KotlinCompile>().matching { it.name in jdk8KotlinCompileTasks }.configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 4 — LA VARIANTE JAVA 8
════════════════════════════════════════════════════════════════════════════════

 Les mêmes sources, compilées une seconde fois en cible 1.8, pour qu'un mod qui vise
 Java 8 à 24 (Minecraft 1.16.5 à 1.21.x) embarque Storify comme un mod pour 26.x (C-65).
 La variante Java 25 est celle de main, la variante par défaut, celle que le POM décrit.

 ── UNE COPIE DES SOURCES ──────────────────────────────────────────────────
 Le jeu de sources `jdk8` lit une copie de src\main\kotlin, posée par syncJdk8Sources
 dans build\jdk8\kotlin : deux jeux de sources sur la même racine feraient à l'IDE deux
 racines identiques, et IntelliJ en retire une d'un module sans dire lequel. Un fichier
 qu'une variante écrirait autrement irait dans src\jdk8\kotlin, posé par-dessus la copie
 au même chemin relatif, un fichier par différence ; il n'y en a aucun aujourd'hui.

 ── UNE FEATURE SOUS LA MÊME COORDONNÉE ────────────────────────────────────
 registerFeature publie la variante avec ses propres configurations (jdk8ApiElements,
 jdk8RuntimeElements, jdk8SourcesElements), son jar au classifier jdk8 et ses sources en
 jdk8-sources. Sa capability est celle du composant, fr.moulou:storify, et non la
 storify-jdk8 que Gradle donnerait par défaut : avec deux capabilities, Gradle ne
 choisirait jamais entre les deux ; avec une seule, il choisit par les attributs, dont
 org.gradle.jvm.version, 8 ou 25. Le plugin Kotlin pose org.gradle.jvm.environment et
 org.jetbrains.kotlin.platform.type sur les variantes de main ; la variante jdk8 les
 reçoit ici, pour que les deux ne diffèrent que par la version de Java. Ses configurations
 de dépendances héritent de celles de main : les deux variantes déclarent les mêmes.

 ── CE QUE L'ESSAI DU 2026-10-18 A MESURÉ (Gradle 9.8.1, Loom 1.18.2) ────
 Un consommateur qui vise 8 ou 21 reçoit la variante 8 et la lance sur son JDK ; un qui
 vise 25 reçoit la 25 ; Maven, qui ne lit que le POM, la 25. L'include de Loom, résolu
 par includeInternal sans attribut de version, embarque la 25 quelle que soit la cible
 du mod, et la 8 dès que le mod pose org.gradle.jvm.version sur cette configuration
 (README, section 4 ; architecture.md, chapitre 14).
════════════════════════════════════════════════════════════════════════════════
*/
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
    registerFeature("jdk8") {
        usingSourceSet(jdk8)
        capability(project.group.toString(), base.archivesName.get(), project.version.toString())
        withSourcesJar()
    }
}

listOf("jdk8ApiElements", "jdk8RuntimeElements").forEach { name ->
    configurations.named(name) {
        attributes {
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }
}

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 5 — LES JARS
════════════════════════════════════════════════════════════════════════════════

 Quatre jars : les classes et les sources de chaque variante. Chacun porte les quatre
 textes de licence de la racine, suffixés du nom de l'artefact pour ne pas entrer en
 collision dans un jar-in-jar, où d'autres jars portent les leurs : LICENSE et
 LICENSE.GPL, la LGPL v3 et la GPL v3 qu'elle incorpore (C-27) ; LICENSE.json5 et NOTICE,
 l'Apache-2.0 de la copie de kotlin-json5 et la mention de cette copie (C-65). Un mod qui
 embarque Storify les redistribue avec elle.

 Le nom se lit ICI, à la configuration, et non dans le `rename` : la closure s'exécute
 pendant la tâche, où toucher `project` est déprécié (erreur franche en Gradle 10). Elle
 ne capture qu'une chaîne locale à la tâche, jamais une valeur du script, que le cache de
 configuration refuse de sérialiser (mesuré : « cannot serialize Gradle script object
 references »).
════════════════════════════════════════════════════════════════════════════════
*/
val licenseFiles = listOf("LICENSE", "LICENSE.GPL", "LICENSE.json5", "NOTICE")

tasks.withType<Jar>().configureEach {
    val licenseSuffix = base.archivesName.get()
    from(licenseFiles) { rename { "${it}_$licenseSuffix" } }
}

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 6 — LES TESTS
════════════════════════════════════════════════════════════════════════════════

 La suite JUnit (356 tests au 2026-10-19, plus le bac à sable local) tourne sur la
 variante Java 25, la toolchain du projet, et écrit ses fichiers dans
 build\tmp\storify-tests. Elle valide la logique une fois : les deux variantes viennent
 des mêmes sources, et la promesse Java 8 se tient à la section 7.

 Les tests se lisent dans le terminal : ce sont des phrases, et les démos de
 src\test\kotlin\fr\moulou\storify\demos impriment ce que la lib fait avant et après
 chaque geste.
════════════════════════════════════════════════════════════════════════════════
*/
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 7 — LES PREUVES DE LA VARIANTE JAVA 8
════════════════════════════════════════════════════════════════════════════════

 Les tests JUnit ne tournent pas sur Java 8 (JUnit 6 exige Java 17), et -Xjdk-release
 garde l'API, pas le comportement : sans ces deux tâches, rien ne tiendrait la promesse
 « tourne sur Java 8 » d'une version à l'autre. Toutes deux sur `check`, donc sur `build`.

 ── CHECKBYTECODE ──────────────────────────────────────────────────────────
 Ouvre les deux jars et lit la version de chaque classe : 69 (Java 25) dans le jar
 principal, 52 (Java 8) dans le jar jdk8, toutes, et nomme celles qui s'en écartent. La
 cible mesurée sur ce qui est publié, et non déduite des options.

 ── L'ESSAI DE FUMÉE ───────────────────────────────────────────────────────
 src\smokeJdk8 est un jeu de sources compilé en cible 1.8 contre le jar jdk8, comme un
 consommateur (les inline de Storify s'y copient depuis du bytecode 52), et lancé par
 smokeJdk8Run sur la toolchain 8, avec les dépendances de la lib aux versions du
 catalogue, celles que fabric-language-kotlin 1.14.2 fournit. Il ouvre, modifie, sauve
 et relit un store JSON, un TOML et un JSON5, vérifie la sauvegarde préservante et la
 clé en double, et sort en échec si un résultat diffère. Son dossier de travail,
 build\tmp\smoke-jdk8, se vide à chaque run.
════════════════════════════════════════════════════════════════════════════════
*/
val smokeJdk8: SourceSet = sourceSets.create("smokeJdk8")
configurations.named("smokeJdk8Implementation") { extendsFrom(configurations.getByName("jdk8Implementation")) }
dependencies {
    "smokeJdk8Implementation"(files(tasks.named("jdk8Jar")))
    "smokeJdk8RuntimeOnly"(libs.slf4j.simple)
}

val checkBytecode = tasks.register("checkBytecode") {
    group = "verification"
    description = "Lit chaque classe des deux jars : bytecode 69 (Java 25) dans le jar principal, 52 (Java 8) dans le jar jdk8 (C-65)."
    val mainJar = tasks.jar.flatMap { it.archiveFile }
    val jdk8Jar = tasks.named<Jar>("jdk8Jar").flatMap { it.archiveFile }
    val mainExpected = targetJavaVersion + 44 // la version majeure d'un fichier de classe : 44 + le Java qu'il vise
    val jdk8Expected = jdk8JavaVersion + 44
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
        verify(mainJar.get().asFile, mainExpected)
        verify(jdk8Jar.get().asFile, jdk8Expected)
    }
}

val smokeJdk8Run = tasks.register<JavaExec>("smokeJdk8Run") {
    group = "verification"
    description = "Lance l'essai de fumée de la variante Java 8 sur une toolchain 8 (C-65)."
    classpath = smokeJdk8.runtimeClasspath
    mainClass = "fr.moulou.storify.smoke.SmokeKt"
    javaLauncher = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk8JavaVersion) }
    args(layout.buildDirectory.dir("tmp/smoke-jdk8").get().asFile.absolutePath)
}

tasks.check { dependsOn(checkBytecode, smokeJdk8Run) }

/*
════════════════════════════════════════════════════════════════════════════════
 SECTION 8 — LA PUBLICATION
════════════════════════════════════════════════════════════════════════════════

 Une publication Maven, `mavenJava`, depuis le composant java : les deux variantes, leurs
 jars et leurs sources, le POM et les métadonnées Gradle (.module), qui seules portent
 les variantes ; un consommateur Maven ne lit que le POM et reçoit la Java 25. Le POM
 nomme la lib, sa licence (LGPL-3.0-only ; la copie de kotlin-json5 se déclare dans le
 jar, LICENSE.json5 et NOTICE, et au README, section 9), l'auteur et le dépôt.

 ── REPSY DERRIÈRE LE JETON ────────────────────────────────────────────────
 Le jeton arrive par la chaîne de secrets (dev-secrets.ps1 -Apply REPSY_MAVEN_TOKEN), en
 variable d'environnement le temps du terminal qui publie. Sans lui, le dépôt Repsy n'est
 pas déclaré, un build ordinaire ne peut rien publier par accident, et `publish` refuse en
 nommant le remède : sans jeton, il n'avait aucun dépôt et réussissait sans rien envoyer,
 le piège refermé chez Outfitter le 2026-09-28. Seule la présence du jeton se capture dans
 la tâche, jamais sa valeur.

 `publishToMavenLocal` répète le circuit sans secret ; une release suit
 git-et-versions.md, chapitre 3, et sa recette vit dans 04-releases\<version>\ du classeur.
════════════════════════════════════════════════════════════════════════════════
*/
val repsyToken: String? = providers.environmentVariable("REPSY_MAVEN_TOKEN").orNull?.takeIf { it.isNotBlank() }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = base.archivesName.get()
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

tasks.named("publish") {
    val hasRepsyToken = repsyToken != null
    doFirst {
        if (!hasRepsyToken) throw GradleException("REPSY_MAVEN_TOKEN is not set, nothing would be published: run dev-secrets.ps1 -Apply REPSY_MAVEN_TOKEN in this terminal first")
    }
}
