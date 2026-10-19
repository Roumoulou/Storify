// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.smoke

import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.system.exitProcess

/**
 * L'essai de fumée de la variante Java 8 (C-65) : compilé en cible 1.8 contre le jar `jdk8`, comme un consommateur, et lancé par la tâche
 * `smokeJdk8Run` sur une toolchain 8, avec les dépendances de la lib aux versions du catalogue, celles que fabric-language-kotlin 1.14.2
 * fournit. Les tests JUnit ne tournent pas sur Java 8 (JUnit 6 exige Java 17), et `-Xjdk-release` garde l'API, pas le comportement :
 * c'est ici que la promesse « tourne sur Java 8 » se vérifie, à chaque `check`. Argument : le dossier de travail, vidé au départ.
 */
@Serializable
data class Cfg(var greeting: String = "Bienvenue", var count: Int = 1)

private val noThreads = StoreConfig(withAutoSave = false, withShutdownHook = false)
private val failures = mutableListOf<String>()

private fun check(condition: Boolean, what: String) {
    if (condition) println("  ok : $what") else {
        println("  ÉCHEC : $what")
        failures += what
    }
}

/** Un store créé depuis le constructeur, modifié, sauvé, puis rouvert : la valeur relue est celle écrite. */
private fun roundTrip(dir: File, extension: String) {
    val path = File(dir, "cfg.$extension").path
    StoreFactory.createFromConstructor<Cfg>(stringPath = path, config = noThreads).use { store ->
        store.set(Cfg::count, 42)
        store.saveImmediate()
    }
    val reread = StoreFactory.createFromConstructor<Cfg>(stringPath = path, config = noThreads).use { it.data }
    check(reread == Cfg("Bienvenue", 42), "[$extension] le store relu vaut Cfg(greeting=Bienvenue, count=42) : $reread")
}

/** La sauvegarde préservante (C-26) sur un fichier écrit à la main : BOM, commentaire, clé nue, apostrophes, virgule traînante. */
private fun preserve(dir: File) {
    val file = File(dir, "kept.json5")
    val bom = 0xFEFF.toChar().toString()
    file.writeText(bom + """
        {
            // garde-moi
            greeting: 'salut',
            count: 1,
        }
    """.trimIndent())
    StoreFactory.createFromConstructor<Cfg>(stringPath = file.path, config = noThreads).use { store ->
        store.set(Cfg::count, 7)
        store.saveImmediate()
    }
    val text = file.readText()
    check("// garde-moi" in text, "[preserve] le commentaire survit à la sauvegarde")
    check("greeting: 'salut'" in text, "[preserve] la clé nue et les apostrophes survivent")
    check("count: 7," in text, "[preserve] la valeur change et la virgule traînante reste")
    check(!text.startsWith(bom), "[preserve] le BOM n'est pas réécrit")
}

/** Une clé en double (C-50, C-55) : refusée avec sa ligne et son chemin. */
private fun duplicate(dir: File) {
    val file = File(dir, "dup.json5")
    file.writeText("""
        {
          greeting: 'a',
          count: 1,
          count: 2,
        }
    """.trimIndent())
    val config = noThreads.copy(createIfMissing = false)
    val error = runCatching { StoreFactory.createFromConstructor<Cfg>(stringPath = file.path, config = config).close() }.exceptionOrNull()
    check(error is StoreDecodeException, "[dup] une clé en double lève StoreDecodeException : ${error?.javaClass?.name}")
    val message = error?.message ?: ""
    check("line 4" in message && "Duplicate key 'count'" in message, "[dup] le message porte la ligne 4 et la clé : $message")
}

fun main(args: Array<String>) {
    val javaVersion = System.getProperty("java.version")
    println("Essai de fumée de la variante jdk8 de Storify sur Java $javaVersion")
    check(javaVersion.startsWith("1.8."), "la JVM est un Java 8 ($javaVersion)")
    val dir = File(args[0]).apply { deleteRecursively(); mkdirs() }
    for (extension in listOf("json", "toml", "json5")) roundTrip(dir, extension)
    preserve(dir)
    duplicate(dir)
    if (failures.isNotEmpty()) {
        println("${failures.size} échec(s) sur Java $javaVersion")
        exitProcess(1)
    }
    println("La variante jdk8 passe sur Java $javaVersion : trois formats, la sauvegarde préservante, la clé en double.")
}
