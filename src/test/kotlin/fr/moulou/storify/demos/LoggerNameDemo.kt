// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/*
 * La démo de C-37 : sous quel nom de logger les lignes de Storify arrivent dans un journal. Le logger de test (slf4j-simple, configuré par
 * src/test/resources/simplelogger.properties) écrit sur System.out une ligne par message, le nom du logger avant le tiret : on capture
 * System.out le temps d'ouvrir et de fermer un store, et on lit ce nom.
 *
 *   1. le défaut : les lignes sortent sous `Storify` (avant C-37, sous le nom de la classe, `fr.moulou.storify.core.BaseStore`) ; dans le
 *      journal d'un serveur Fabric, qui affiche le nom du logger entre parenthèses, elles ne sont pas sous `(aegisperms)` ;
 *   2. depuis C-37 : `StoreConfig(loggerName = "aegisperms")`, et les mêmes lignes arrivent sous le logger du mod, préfixe `[Storify]` gardé.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class LoggerNameDemoTest {

    /** Capture ce que le logger de test écrit sur System.out pendant [block], ligne par ligne. */
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

    /** Le nom du logger d'une ligne de slf4j-simple : `[thread] NIVEAU <nom> - message`. */
    private fun loggerNameOf(line: String): String = line.substringAfter("] ").substringAfter(" ").substringBefore(" - ")

    private fun openAndClose(config: StoreConfig): List<String> = captureLog {
        StoreFactory.create<PlainData>(newStorePath("permissions.json").toString(), config = config).close()
    }

    @Test
    fun `étape 1, sans loggerName les lignes d'un store sortent sous Storify`() {
        val lines = openAndClose(StoreConfig(withAutoSave = false, readOnly = true))
        lines.forEach { println("    $it") }
        val names = lines.map(::loggerNameOf).distinct()
        println("    nom du logger : $names ; le journal du mod s'appellerait « aegisperms » : ces lignes n'y sont pas")
        check(names == listOf("Storify"))
    }

    @Test
    fun `étape 2, depuis C-37, loggerName range les mêmes lignes sous le journal du mod`() {
        val lines = openAndClose(StoreConfig(withAutoSave = false, readOnly = true, loggerName = "aegisperms"))
        lines.forEach { println("    $it") }
        val names = lines.map(::loggerNameOf).distinct()
        println("    nom du logger : $names ; le préfixe [Storify] reste dans le message, il dit d'où vient la ligne")
        check(names == listOf("aegisperms") && lines.all { "[Storify]" in it })
    }
}
