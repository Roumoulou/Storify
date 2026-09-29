// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.StorifyException
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.io.path.writeText

/*
 * La démo de C-33 : ce qu'un mod reçoit quand un fichier ne se décode pas. Quatre pannes courantes d'un fichier édité à la main (une virgule
 * en trop, une clé inconnue, un TOML tronqué, un JSON5 tronqué), puis la question du consommateur : que faut-il attraper pour couvrir tout ce
 * qui empêche d'ouvrir un store ? Chaque étape imprime la classe de l'exception, sa lignée, et son message tel quel.
 * Avant C-33 : la SerializationException nue de kotlinx (un offset, un conseil pour le développeur, pas de fichier), le « (L2) » de tomlkt,
 * la Json5ParseException (une IllegalStateException) ; deux familles à attraper. Depuis : toute panne de lecture ou de parseur arrive en
 * StoreDecodeException (chemin, format, ligne au mieux, cause conservée), sous l'ancêtre StorifyException qu'elle partage avec
 * ValidationException, un seul catch.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class DecodeErrorsDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    private fun lineage(e: Throwable): String = generateSequence<Class<*>>(e.javaClass) { it.superclass }.map { it.simpleName }.takeWhile { it != "Throwable" }.joinToString(" > ")

    private fun show(label: String, attempt: () -> Unit) {
        val failure = runCatching(attempt).exceptionOrNull()
        if (failure == null) {
            println("$label : passé")
            return
        }
        println("$label")
        println("    classe  : ${lineage(failure)}")
        println("    message : ${failure.message?.lines()?.joinToString(" | ")?.take(230)}")
    }

    @Test
    fun `étape 1, un JSON avec une virgule de trop`() {
        val path = newStorePath("config.json").also { it.writeText("{\n  \"name\": \"steve\",\n  \"count\": 1,\n  \"tags\": [],\n}") }
        show("JSON mal formé") { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).close() }
    }

    @Test
    fun `étape 2, un JSON avec une clé inconnue`() {
        val path = newStorePath("config.json").also { it.writeText("{\n  \"name\": \"steve\",\n  \"colour\": \"red\",\n  \"count\": 1,\n  \"tags\": []\n}") }
        show("JSON, clé inconnue") { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).close() }
    }

    @Test
    fun `étape 3, un TOML tronqué`() {
        val path = newStorePath("config.toml").also { it.writeText("name = \"steve\"\ncount = \n") }
        show("TOML mal formé") { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).close() }
    }

    @Test
    fun `étape 4, un JSON5 tronqué`() {
        val path = newStorePath("config.json5").also { it.writeText("{\n  name: 'steve',\n  count: \n") }
        show("JSON5 mal formé") { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).close() }
    }

    @Test
    fun `étape 5, ce qu'un mod doit attraper pour couvrir tout ce qui empêche d'ouvrir`() {
        val malformed = newStorePath("a.json").also { it.writeText("{ cassé") }
        val invalid = newStorePath("b.json").also { it.writeText("{\n  \"name\": \"\"\n}") }
        show("fichier mal formé") { StoreFactory.create<PlainData>(malformed.toString(), config = noAutoSave).close() }
        show("fichier bien formé mais invalide") { StoreFactory.createFromConstructor<AnnotatedValidatedData>(invalid.toString()).close() }

        val opens = listOf<() -> Unit>(
            { StoreFactory.create<PlainData>(malformed.toString(), config = noAutoSave).close() },
            { StoreFactory.createFromConstructor<AnnotatedValidatedData>(invalid.toString()).close() },
        )
        check(opens.all { runCatching(it).exceptionOrNull() is StorifyException })
        println("    les deux sont des StorifyException : un seul catch suffit")
    }
}
