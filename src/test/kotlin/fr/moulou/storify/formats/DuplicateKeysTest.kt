// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.formats

import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validateFile
import fr.moulou.storify.validation.DuplicateKeyException
import fr.moulou.storify.validation.JsonDuplicateKeys
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Les clés en double (C-50) : le lecteur JSON strict et le format JSON5 refusent une clé déclarée deux fois dans le même objet, par
 * `StoreDecodeException` avec la ligne de la seconde occurrence et une `DuplicateKeyException` en cause, à l'ouverture, au rechargement et dans
 * `validateFile` ; le lecteur tolérant laisse passer ; TOML refusait déjà, par sa spécification.
 */
@Serializable
data class DupGroup(var priority: Int = 0, var prefix: String = "")

@Serializable
data class DupFile(var version: Int = 1, var defaults: DupGroup = DupGroup(), var groups: MutableMap<String, DupGroup> = mutableMapOf())

class DupFileValidator : Validator<DupFile> {
    override fun validate(data: DupFile, ctx: ValidationContext) {
        ctx.check(data.version >= 1, "version", "must be at least 1", data.version)
    }
}

class DuplicateKeysTest {

    private val strict = JsonFormat()
    private val noAutoSave = StoreConfig(withAutoSave = false)

    private val rootDuplicate = "{\n  \"version\": 1,\n  \"version\": 2,\n  \"groups\": {}\n}"
    private val nestedDuplicate = "{\n  \"version\": 1,\n  \"defaults\": {\n    \"priority\": 1,\n    \"priority\": 2,\n    \"prefix\": \"\"\n  },\n  \"groups\": {}\n}"
    private val mapDuplicate = "{\n  \"version\": 1,\n  \"groups\": {\n    \"vip\": { \"priority\": 10 },\n    \"vip\": { \"priority\": 20 }\n  }\n}"

    private fun file(name: String, text: String): Path = newStorePath(name).also { it.writeText(text) }

    /** La faute enveloppée, comme la reçoit un consommateur : par `validateFile`, qui décode par la même voie que les stores. */
    private fun failure(format: StoreFormat, path: Path): StoreDecodeException =
        assertThrows(StoreDecodeException::class.java) { format.validateFile(path, DupFile.serializer(), DupFileValidator()) }

    private fun assertDuplicate(failure: StoreDecodeException, key: String, line: Int) {
        assertEquals(line, failure.line)
        val cause = assertInstanceOf(DuplicateKeyException::class.java, failure.cause)
        assertEquals(key, cause.key)
        assertEquals(line, cause.line)
        assertTrue(failure.message!!.contains("at line $line") && failure.message!!.contains("Duplicate key '$key'"))
    }

    @Test
    fun `un doublon à la racine est refusé par le lecteur strict, avec la ligne de la seconde occurrence`() {
        assertDuplicate(failure(strict, file("dup.json", rootDuplicate)), "version", 3)
    }

    @Test
    fun `un doublon dans un objet imbriqué est refusé, avec sa ligne`() {
        assertDuplicate(failure(strict, file("dup.json", nestedDuplicate)), "priority", 5)
    }

    @Test
    fun `un doublon dans une map est refusé, avec sa ligne`() {
        assertDuplicate(failure(strict, file("dup.json", mapDuplicate)), "vip", 5)
    }

    @Test
    fun `la même clé dans deux objets différents est acceptée`() {
        val path = file("ok.json", "{\n  \"version\": 1,\n  \"defaults\": { \"priority\": 1 },\n  \"groups\": {\n    \"vip\": { \"priority\": 1 },\n    \"mod\": { \"priority\": 2 }\n  }\n}")
        val decoded = strict.decodeFromPath(DupFile.serializer(), path)
        assertEquals(setOf("vip", "mod"), decoded.groups.keys)
        assertEquals(1, decoded.defaults.priority)
    }

    @Test
    fun `une clé échappée est reconnue comme doublon, et le contrat brut lève la DuplicateKeyException nue, comme kotlinx lève les siennes`() {
        val path = file("escaped.json", "{\"ab\": 1, \"a\\u0062\": 2}")
        val raw = assertThrows(DuplicateKeyException::class.java) { strict.decodeFromPath(MapSerializer(String.serializer(), Int.serializer()), path) }
        assertEquals("ab", raw.key)
        assertEquals(1, raw.line)
        assertDuplicate(failure(strict, file("escaped2.json", "{\"version\": 1, \"\\u0076ersion\": 2, \"groups\": {}}")), "version", 1)
    }

    @Test
    fun `deux clés sur une seule ligne sont vues`() {
        assertDuplicate(failure(strict, file("compact.json", "{\"version\": 1, \"version\": 2, \"groups\": {}}")), "version", 1)
    }

    @Test
    fun `le scanner ignore une clé dans une chaîne, et un doublon dans deux éléments d'un tableau n'en est pas un`() {
        assertNull(JsonDuplicateKeys.firstDuplicate("{\"a\": \"x: 1, \\\"a\\\": 2\", \"b\": 1}"))
        assertNull(JsonDuplicateKeys.firstDuplicate("[{\"a\": 1}, {\"a\": 2}]"))
        assertEquals(JsonDuplicateKeys.Duplicate("a", 2), JsonDuplicateKeys.firstDuplicate("{\n  \"a\": 1, \"a\": 2}"))
        val path = file("string.json", "{\n  \"version\": 1,\n  \"defaults\": { \"priority\": 0, \"prefix\": \"version: 1, \\\"version\\\": 2\" },\n  \"groups\": {}\n}")
        assertEquals("version: 1, \"version\": 2", strict.decodeFromPath(DupFile.serializer(), path).defaults.prefix)
    }

    @Test
    fun `un fichier mal formé reste une faute du parseur, diagnostiquée avant toute recherche de doublon`() {
        val failure = failure(strict, file("broken.json", "{\n  \"version\": 1,\n  \"version\": [1, 2")) // le doublon n'est pas cherché dans un texte que le parseur refuse
        assertFalse(failure.cause is DuplicateKeyException)
        assertInstanceOf(kotlinx.serialization.SerializationException::class.java, failure.cause)
    }

    @Test
    fun `le lecteur tolérant laisse passer un doublon, la dernière valeur gagne, comme un Json qui admet les commentaires`() {
        val path = file("dup.json", rootDuplicate)
        assertEquals(2, JsonFormat.lenient().decodeFromPath(DupFile.serializer(), path).version)
        assertEquals(2, JsonFormat(Json { allowComments = true }).decodeFromPath(DupFile.serializer(), path).version)
        assertEquals(2, JsonFormat(Json { isLenient = true }).decodeFromPath(DupFile.serializer(), path).version)
    }

    @Test
    fun `à l'ouverture d'un store, un doublon lève StoreDecodeException`() {
        val path = file("dup.json", mapDuplicate)
        val failure = assertThrows(StoreDecodeException::class.java) { StoreFactory.createFromConstructor<DupFile>(path.toString(), config = noAutoSave) }
        assertDuplicate(failure, "vip", 5)
    }

    @Test
    fun `au rechargement, un doublon est refusé et la mémoire reste intacte`() {
        val path = newStorePath("dup.json")
        StoreFactory.createFromConstructor<DupFile>(path.toString(), config = noAutoSave).use { store ->
            path.writeText(rootDuplicate) // l'admin édite le fichier
            val failure = assertThrows(StoreDecodeException::class.java) { store.reloadFromFile() }
            assertDuplicate(failure, "version", 3)
            assertEquals(1, store.data.version)
            assertFalse(store.isDirty)
        }
    }

    @Test
    fun `validateFile refuse un doublon, avec et sans store`() {
        val path = file("dup.json", nestedDuplicate)
        assertDuplicate(assertThrows(StoreDecodeException::class.java) { strict.validateFile(path, DupFileValidator()) }, "priority", 5)
        val storePath = newStorePath("store.json")
        StoreFactory.createFromConstructor<DupFile>(storePath.toString(), config = noAutoSave, validator = DupFileValidator()).use { store ->
            storePath.writeText(nestedDuplicate)
            assertDuplicate(assertThrows(StoreDecodeException::class.java) { store.validateFile() }, "priority", 5)
            assertEquals(0, store.data.defaults.priority)
        }
    }

    @Test
    fun `en JSON5, un doublon est refusé avec sa ligne, clés nues, apostrophes et échappements compris`() {
        val json5 = Json5Format()
        assertDuplicate(failure(json5, file("dup.json5", "{\n  // la version\n  version: 1,\n  'version': 2,\n}")), "version", 4)
        assertDuplicate(failure(json5, file("nested.json5", "{\n  defaults: {\n    priority: 1,\n    priority: 2,\n  },\n}")), "priority", 4)
        val escaped = file("escaped.json5", "{ab: 1, 'a\\u0062': 2}")
        val raw = assertThrows(DuplicateKeyException::class.java) { json5.decodeFromPath(MapSerializer(String.serializer(), Int.serializer()), escaped) }
        assertEquals("ab", raw.key)
        assertEquals(setOf("vip", "mod"), json5.decodeFromPath(DupFile.serializer(), file("ok.json5", "{groups: {vip: {priority: 1}, mod: {priority: 1}}}")).groups.keys)
    }

    @Test
    fun `en JSON5, un doublon au rechargement laisse la mémoire intacte, et un texte mal formé reste une faute du parseur`() {
        val path = newStorePath("dup.json5")
        StoreFactory.createFromConstructor<DupFile>(path.toString(), config = noAutoSave).use { store ->
            path.writeText("{\n  version: 1,\n  version: 2,\n}")
            assertDuplicate(assertThrows(StoreDecodeException::class.java) { store.reloadFromFile() }, "version", 3)
            assertEquals(1, store.data.version)
        }
        val broken = failure(Json5Format(), file("broken.json5", "{\n  version: 1,\n  version: [1, 2"))
        assertFalse(broken.cause is DuplicateKeyException)
    }

    @Test
    fun `en TOML, tomlkt refuse déjà un doublon, avec la ligne de la seconde occurrence`() {
        val failure = failure(TomlFormat(), file("dup.toml", "version = 1\nversion = 2\n"))
        assertEquals(2, failure.line)
        assertFalse(failure.cause is DuplicateKeyException)
    }
}
