// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-30 : pourquoi un store « en lecture seule par discipline » ne l'est pas. Le scénario est celui d'AegisPerms : un fichier de
 * droits que le mod lit et qu'une application de bureau écrit. Trois gestes anodins du mod réécrivent ce fichier par-dessus l'édition de
 * l'autre application : une transaction au bloc vide (étape 1), un saveImmediate sans modification (étape 2), et le hook d'arrêt de la JVM
 * dès que le store se croit modifié (étape 3). Depuis C-30, readOnly refuse tout ça (étape 4) : toute écriture lève, ni hook ni planificateur ;
 * withShutdownHook débraye le hook seul, et createIfMissing décide si le fichier initial s'écrit une fois, ou jamais rien sur le disque.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class ReadOnlyDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false) // pas de tick d'auto-save : seul le code, ou le hook, peut écrire

    private fun nameLine(text: String) = text.lines().first { "\"name\"" in it }.trim()

    @Test
    fun `étape 1, un store que le code n'écrit jamais réécrit quand même le fichier, il suffit d'une transaction au bloc vide`() {
        val path = newStorePath("permissions.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = noAutoSave)

        path.writeText(path.readText().replace("\"default\"", "\"édité par l'application de bureau\""))
        println("fichier après l'édition externe : ${nameLine(path.readText())}")

        store.transaction { }                                         // rien ne change en mémoire...
        println("dirty après une transaction vide : ${store.isDirty}") // ... mais le store se croit modifié

        store.close()                                                 // la sauvegarde d'adieu écrit la mémoire par-dessus l'édition
        println("fichier après close() : ${nameLine(path.readText())}")
        check(path.readText().contains("\"default\""))
    }

    @Test
    fun `étape 2, saveImmediate écrit sans condition, même sans rien à sauver`() {
        val path = newStorePath("permissions.json")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            path.writeText(path.readText().replace("\"default\"", "\"édité par l'application de bureau\""))

            store.saveImmediate() // aucune modification en mémoire, le fichier est réécrit quand même
            println("fichier après saveImmediate() : ${nameLine(path.readText())}")
            check(path.readText().contains("\"default\""))
        }
    }

    @Test
    fun `étape 3, le hook d'arrêt de la JVM est armé pour chaque store, et écrit tout store dirty à l'extinction`() {
        val path = newStorePath("permissions.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = noAutoSave)
        store.transaction { } // dirty, comme à l'étape 1
        path.writeText(path.readText().replace("\"default\"", "\"édité par l'application de bureau\""))

        store.runShutdownHook() // le corps du hook, celui que la JVM exécute à l'arrêt, joué ici sans éteindre la JVM
        println("fichier après le hook d'arrêt : ${nameLine(path.readText())}")
        check(path.readText().contains("\"default\""))
        store.close()
    }

    @Test
    fun `étape 4, depuis C-30, readOnly refuse les trois gestes et le fichier reste à l'application de bureau`() {
        val path = newStorePath("permissions.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(readOnly = true))
        path.writeText(path.readText().replace("\"default\"", "\"édité par l'application de bureau\""))

        val attempts = listOf<Pair<String, () -> Unit>>("transaction vide" to { store.transaction { } }, "saveImmediate" to { store.saveImmediate() })
        attempts.forEach { (gesture, attempt) ->
            val failure = runCatching { attempt() }.exceptionOrNull()
            println("$gesture : ${failure?.let { "${it::class.simpleName}: ${it.message}" } ?: "passé (inattendu)"}")
        }
        store.runShutdownHook() // le corps du hook, qui n'est même plus armé : rien à sauver, rien d'écrit
        store.close()
        println("fichier après tout ça : ${nameLine(path.readText())}")
        check(path.readText().contains("édité par l'application de bureau"))
    }
}