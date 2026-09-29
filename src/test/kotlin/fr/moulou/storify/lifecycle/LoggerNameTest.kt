// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.lifecycle

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Le logger nommé (C-37) : `Storify` par défaut, le nom que `StoreConfig.loggerName` donne sinon, pour que les lignes d'un store paraissent
 * sous le journal du mod qui le possède ; le préfixe `[Storify]` reste. Le logger de test (slf4j-simple) écrit sur System.out une ligne par
 * message, le nom du logger avant le tiret : on le capture pour lire ce nom.
 */
class LoggerNameTest {

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
    fun `le logger d'un store se nomme Storify par défaut`() {
        StoreFactory.create<PlainData>(newStorePath("default.json").toString(), config = StoreConfig(withAutoSave = false)).use { store ->
            assertEquals("Storify", store.loggerName)
        }
    }

    @Test
    fun `loggerName range les lignes du store sous le journal du mod, préfixe gardé`() {
        val path = newStorePath("permissions.json")

        val lines = captureLog {
            StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, readOnly = true, loggerName = "aegisperms")).close()
        }

        assertTrue(lines.isNotEmpty())
        lines.forEach { line -> assertTrue(line.contains(" aegisperms - [Storify] "), line) }
    }
}
