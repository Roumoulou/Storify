// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.SaveOperation
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-47 : le rechargement d'un store modifié, le cycle d'édition à chaud du banc (éditer le fichier, puis /storibench reload) joué
 * sur un store qui porte une modification en mémoire pas encore sauvegardée. La règle ne change pas : l'utilisateur édite, puis recharge
 * lui-même. Deux questions : que devient cette modification, et que devient le drapeau dirty une fois la mémoire remplacée par le fichier ?
 *
 * Avant C-47, la modification disparaissait sans un mot et le drapeau dirty restait levé : la sauvegarde d'adieu réécrivait un fichier qui
 * n'avait pas changé, et le JSON compact de l'admin ressortait réindenté. Depuis : le fichier gagne et le store le dit au log en warn, le
 * dirty retombe, et devant un auditeur de save la référence du prochain old devient la racine rechargée.
 *
 *   1. une modification en mémoire, un fichier édité à la main, un rechargement : la modification est écartée, le log le dit, le dirty retombe ;
 *   2. la sauvegarde d'adieu ne part plus, et le fichier de l'admin reste tel quel, mise en forme comprise.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class ReloadDirtyDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    /** Ce que le logger de test écrit sur System.out pendant [block], ligne par ligne. */
    private fun captureLog(block: () -> Unit): List<String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    @Test
    fun `étape 1, depuis C-47, une modification en mémoire, un fichier édité à la main, un rechargement qui le dit`() {
        val path = newStorePath("config.json")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            store.set(PlainData::count, 7) // modifié en mémoire, pas sauvegardé
            println("    en mémoire avant le rechargement : count = ${store.data.count}, dirty = ${store.isDirty}")

            path.writeText(path.readText().replace("\"default\"", "\"édité par l'admin\"")) // l'admin édite le fichier, sans savoir pour count
            val log = captureLog { store.reloadFromFile() }

            log.forEach { println("    log : $it") }
            println("    en mémoire après le rechargement : name = ${store.data.name}, count = ${store.data.count}, dirty = ${store.isDirty}")
            check(store.data.count == 1 && !store.isDirty && log.any { "discards unsaved in-memory changes" in it })
        }
    }

    @Test
    fun `étape 2, depuis C-47, le dirty retombé, la sauvegarde d'adieu ne part plus et le fichier de l'admin reste tel quel`() {
        val path = newStorePath("config.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = noAutoSave)
        val saves = mutableListOf<SaveOperation<PlainData>>()
        store.registerOnSave { saves.add(it as SaveOperation<PlainData>) }

        store.set(PlainData::count, 7)
        path.writeText("{\"name\": \"édité par l'admin\", \"count\": 1, \"tags\": [\"a\"]}") // l'admin écrit compact, à sa façon
        captureLog { store.reloadFromFile() }
        val afterReload = path.readText()
        println("    après le rechargement : dirty = ${store.isDirty}, fichier : $afterReload")

        store.close() // la sauvegarde d'adieu ne part que dirty : elle ne part pas
        println("    après close() : ${saves.size} sauvegarde, fichier : ${path.readText()}")
        check(saves.isEmpty() && path.readText() == afterReload)
    }
}
