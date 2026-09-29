// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validateFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText

// ─── La fixture : un effectif dont les chemins traversent une map, validée par validateEach ─────

@Serializable
data class Member(var joinCount: Int = 0)

@Serializable
data class Roster(var name: String = "roster", var players: MutableMap<String, Member> = mutableMapOf("steve" to Member()))

object MemberValidator : Validator<Member> {
    override fun validate(data: Member, ctx: ValidationContext) {
        ctx.check(data.joinCount >= 0, "joinCount", "must not be negative", data.joinCount)
    }
}

class RosterValidator : Validator<Roster> {
    override fun validate(data: Roster, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.validateEach("players", data.players, MemberValidator)
    }
}

/**
 * `validateFile` (C-32) : un fichier se valide sans store, décodé, validé et enrichi des lignes ; le store inspecte le sien sur le disque sans
 * toucher la mémoire ; un format sans localisateur rend les erreurs sans lignes ; un fichier qui ne se décode pas lève.
 */
class ValidateFileTest {

    private val invalidJson = "{\n  \"name\": \"\",\n  \"players\": {\n    \"steve\": {\n      \"joinCount\": -1\n    }\n  }\n}"

    @Test
    fun `validateFile d'un format décode, valide et enrichit les lignes`() {
        val path = newStorePath("roster.json")
        path.writeText(invalidJson)

        val result = JsonFormat().validateFile(path, RosterValidator())

        val failure = assertInstanceOf(ValidationResult.Failure::class.java, result)
        assertEquals(listOf(2, 5), failure.errors.map { it.line })
        assertEquals("Roster.players[steve]", failure.errors[1].path) // le chemin bâti par validateEach sur la map
    }

    @Test
    fun `validateFile en TOML rend les erreurs sans lignes`() {
        val path = newStorePath("roster.toml")
        path.writeText("name = \"\"\n[players.steve]\njoinCount = -1\n")

        val result = TomlFormat().validateFile(path, RosterValidator())

        val failure = assertInstanceOf(ValidationResult.Failure::class.java, result)
        assertEquals(2, failure.errorCount)
        assertTrue(failure.errors.all { it.line == null })
    }

    @Test
    fun `le store inspecte son fichier sur le disque sans toucher la mémoire`() {
        val path = newStorePath("roster-store.json")
        StoreFactory.createFromConstructor<Roster>(path.toString(), config = StoreConfig(withAutoSave = false), validator = RosterValidator()).use { store ->
            path.writeText(invalidJson)

            val onDisk = assertInstanceOf(ValidationResult.Failure::class.java, store.validateFile())
            assertEquals(listOf(2, 5), onDisk.errors.map { it.line })

            assertEquals("roster", store.data.name) // la mémoire n'a pas bougé
            assertTrue(store.validateNow().isValid)  // et reste valide
        }
    }

    @Test
    fun `validateFile lève sur un fichier qui ne se décode pas`() {
        val path = newStorePath("broken.json")
        path.writeText("{ cassé")

        assertThrows(SerializationException::class.java) { JsonFormat().validateFile(path, RosterValidator()) }
    }
}
