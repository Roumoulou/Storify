// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.lifecycle

import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.FarmConfig
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validation.PathSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * La ligne et le chemin d'une faute de décodage sans offset (C-56) : quand le message du parseur nomme le chemin de la valeur (« at path
 * $.mood », kotlinx) sans offset, la ligne vient du localisateur du format et le chemin s'expose en `valuePath` ; une fin de fichier atteinte
 * rend la dernière ligne du fichier. Les fautes à offset gardent leur ligne, et JSON5 et TOML, dont le message ne dit pas où, restent sans
 * ligne ni chemin.
 */
class DecodeErrorLineTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    private fun openFarm(path: Path): StoreDecodeException = assertThrows(StoreDecodeException::class.java) { StoreFactory.createFromConstructor<FarmConfig>(path.toString(), config = noAutoSave) }

    private fun keys(vararg names: String): List<PathSegment> = names.map { PathSegment.Key(it) }

    // ─── La valeur hors domaine, par le chemin que le message nomme ───

    @Test
    fun `une valeur d'énumération inconnue à la racine a sa ligne et son chemin`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"FURIOUS\",\n  \"favorite\": { \"name\": \"rex\", \"mood\": \"CALM\" },\n  \"pets\": {}\n}") }
        val failure = openFarm(path)
        assertEquals(2, failure.line)
        assertEquals(keys("mood"), failure.valuePath)
        assertTrue(failure.message!!.contains("at line 2"))
    }

    @Test
    fun `une valeur d'énumération inconnue dans un objet imbriqué`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\",\n  \"favorite\": {\n    \"name\": \"rex\",\n    \"mood\": \"FURIOUS\"\n  },\n  \"pets\": {}\n}") }
        val failure = openFarm(path)
        assertEquals(5, failure.line)
        assertEquals(keys("favorite", "mood"), failure.valuePath)
    }

    @Test
    fun `une valeur d'énumération inconnue sous une clé de map`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\",\n  \"favorite\": { \"name\": \"rex\", \"mood\": \"CALM\" },\n  \"pets\": {\n    \"rex\": {\n      \"name\": \"rex\",\n      \"mood\": \"FURIOUS\"\n    }\n  }\n}") }
        val failure = openFarm(path)
        assertEquals(7, failure.line)
        assertEquals(keys("pets", "rex", "mood"), failure.valuePath)
    }

    // ─── La fin de fichier atteinte ───

    @Test
    fun `un fichier vide rend la ligne 1 et un chemin vide`() {
        val path = newStorePath("farm.json").also { it.writeText("") }
        val failure = openFarm(path)
        assertEquals(1, failure.line)
        assertEquals(emptyList<PathSegment>(), failure.valuePath)
    }

    @Test
    fun `un fichier coupé après une valeur rend sa dernière ligne`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\"\n") }
        val failure = openFarm(path)
        assertEquals(3, failure.line) // la ligne où le fichier s'arrête, celle qu'un éditeur montre à sa fin
        assertEquals(emptyList<PathSegment>(), failure.valuePath)
    }

    // ─── Les fautes à offset gardent leur ligne ───

    @Test
    fun `un fichier coupé au milieu d'une chaîne garde la ligne de son offset, et gagne le chemin`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CA") }
        val failure = openFarm(path)
        assertEquals(2, failure.line)
        assertEquals(keys("mood"), failure.valuePath)
    }

    @Test
    fun `une clé inconnue garde la ligne de son offset, son chemin est la racine`() {
        val path = newStorePath("unknown.json").also { it.writeText("{\n  \"name\": \"s\",\n  \"colour\": \"red\",\n  \"count\": 1,\n  \"tags\": []\n}") }
        val failure = assertThrows(StoreDecodeException::class.java) { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave) }
        assertEquals(3, failure.line)
        assertEquals(emptyList<PathSegment>(), failure.valuePath)
    }

    // ─── JSON5 et TOML, dont le message ne dit pas où ───

    @Test
    fun `JSON5 et TOML restent sans ligne ni chemin pour une valeur hors domaine`() {
        val json5 = newStorePath("farm.json5").also { it.writeText("{\n  mood: 'FURIOUS',\n  favorite: { name: 'rex', mood: 'CALM' },\n  pets: {}\n}") }
        val toml = newStorePath("farm.toml").also { it.writeText("mood = \"FURIOUS\"\n\n[favorite]\nname = \"rex\"\nmood = \"CALM\"\n\n[pets]\n") }
        for (path in listOf(json5, toml)) {
            val failure = openFarm(path)
            assertNull(failure.line)
            assertEquals(emptyList<PathSegment>(), failure.valuePath)
        }
    }
}
