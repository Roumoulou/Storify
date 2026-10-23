// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.formats

import dev.eav.tomlkt.TomlComment
import fr.moulou.storify.CommentPolicy
import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreComment
import fr.moulou.storify.StoreConfiguration
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.encodeToPathAtomically
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Les commentaires depuis le code (C-64) : `@StoreComment` écrit en JSON5 au-dessus de la clé, dans un fichier neuf toujours, dans un fichier
 * existant selon la politique ; ignoré en JSON strict et en TOML, qui garde `@TomlComment` ; muet sur une racine sans classe.
 */
class CommentsTest {

    private val format = Json5Format()

    @Test
    fun `un fichier neuf porte les commentaires du code, en tête et au-dessus de chaque clé`() {
        val path = newStorePath("new.json5")

        format.encodeToPath(CommentedConfig.serializer(), CommentedConfig(), path)

        val text = path.readText()
        assertTrue(text.startsWith("// La config du mod Demo : éditez les valeurs, pas les clés\n{"), text)
        assertTrue(text.contains("\n    // Le message d'accueil\n    greeting: 'Bienvenue'"), text)
        assertTrue(text.contains("\n    // Entre 1 et 100\n    // Au-delà, la commande refuse\n    maxHomes: 3"), text)
        assertFalse(lineBefore(text, "silent").startsWith("//"), "une propriété sans annotation reste nue")
        assertTrue(text.contains("\n    // Les délais, en secondes\n    delays: {\n        // Avant la téléportation\n        warmup: 3"), text)
        assertTrue(text.contains("\n    // Par monde : le nom du monde vers sa règle\n    perWorld: {"), text)
        assertFalse(lineBefore(text, "overworld").startsWith("//"), "une entrée de map n'a pas de commentaire")
        assertTrue(text.contains("\n    // Une première ligne\n    // et une seconde, coupée par le code\n    split: true"), text)
        assertEquals(9, commentLines(text))
        assertEquals(CommentedConfig(), format.decodeFromPath(CommentedConfig.serializer(), path))
    }

    @Test
    fun `FILE_WINS, le fichier garde ses commentaires, une clé nue reste nue, une clé neuve reçoit le sien`() {
        val path = newStorePath("file-wins.json5")
        val previous = "{\n    // mon accueil\n    greeting: 'Salut',\n    maxHomes: 5,\n    // à moi\n    silent: 2, // compteur\n}\n"

        preserve(CommentedConfig(greeting = "Salut", maxHomes = 5, silent = 2), path, previous)

        val text = path.readText()
        assertFalse(text.startsWith("//"), "la racine existait : pas d'en-tête ajouté")
        assertEquals("// mon accueil", lineBefore(text, "greeting"))
        assertFalse(text.contains("Le message d'accueil"))
        assertFalse(text.contains("Entre 1 et 100"), "la clé nue reste nue")
        assertEquals("// à moi", lineBefore(text, "silent"))
        assertTrue(text.contains("silent: 2, // compteur"))
        assertEquals("// Les délais, en secondes", lineBefore(text, "delays"))
        assertEquals("// Avant la téléportation", lineBefore(text, "warmup"))
        assertEquals("// Par monde : le nom du monde vers sa règle", lineBefore(text, "perWorld"))
        assertEquals("// et une seconde, coupée par le code", lineBefore(text, "split"))
        assertEquals(CommentedConfig(greeting = "Salut", maxHomes = 5, silent = 2), format.decodeFromPath(CommentedConfig.serializer(), path))
    }

    @Test
    fun `FILE_WINS, un save sans changement reste identique à l'octet, commentaires du code compris`() {
        val path = newStorePath("idempotent.json5")
        format.encodeToPath(CommentedConfig.serializer(), CommentedConfig(), path)
        val written = path.readText()

        preserve(CommentedConfig(), path, written)

        assertEquals(written, path.readText())
    }

    @Test
    fun `CODE_WINS, les commentaires de tête des clés commentées sont remplacés, le reste reste, et le second save est identique`() {
        val path = newStorePath("code-wins.json5")
        val previous = """
            // un en-tête de l'admin
            {
                // mon accueil
                greeting: 'Salut',
                // Entre 1 et 100 (ancien)
                // Au-delà (ancien)
                maxHomes: 5,
                // à moi
                silent: 2, // compteur
                delays: {
                    warmup: 1,
                    cooldown: 2,
                },
                perWorld: {},
                split: false,
            }
        """.trimIndent() + "\n"
        val data = CommentedConfig(greeting = "Salut", maxHomes = 5, silent = 2, delays = Delays(1, 2), perWorld = mutableMapOf(), split = false)

        preserve(data, path, previous, CommentPolicy.CODE_WINS)

        val text = path.readText()
        assertTrue(text.startsWith("// La config du mod Demo : éditez les valeurs, pas les clés\n{"), text)
        assertFalse(text.contains("un en-tête de l'admin"))
        assertEquals("// Le message d'accueil", lineBefore(text, "greeting"))
        assertFalse(text.contains("mon accueil"))
        assertTrue(text.contains("\n    // Entre 1 et 100\n    // Au-delà, la commande refuse\n    maxHomes: 5"), text)
        assertFalse(text.contains("(ancien)"))
        assertEquals("// à moi", lineBefore(text, "silent"))
        assertTrue(text.contains("silent: 2, // compteur"))
        assertEquals("// Les délais, en secondes", lineBefore(text, "delays"))
        assertEquals("// Avant la téléportation", lineBefore(text, "warmup"))
        assertFalse(text.contains("\n\n"), "aucune ligne vide laissée par un commentaire retiré : $text")
        assertEquals(data, format.decodeFromPath(CommentedConfig.serializer(), path))

        preserve(data, path, text, CommentPolicy.CODE_WINS)
        assertEquals(text, path.readText())
    }

    @Test
    fun `la politique vient de StoreConfiguration, et une StoreConfig explicite la remplace`() {
        val annotated = newStorePath("annotated.json5")
        annotated.writeText("{\n    // mon accueil\n    greeting: 'Salut',\n}\n")
        StoreFactory.createFromConstructor<CodeWinsConfig>(annotated.toString()).use { store ->
            store.set(CodeWinsConfig::greeting, "Hello")
            store.saveImmediate()
        }
        val codeWins = annotated.readText()
        assertEquals("// Le message d'accueil", lineBefore(codeWins, "greeting"))
        assertFalse(codeWins.contains("mon accueil"))

        val explicit = newStorePath("explicit.json5")
        explicit.writeText("{\n    // mon accueil\n    greeting: 'Salut',\n}\n")
        StoreFactory.createFromConstructor<CodeWinsConfig>(explicit.toString(), config = StoreConfig(withAutoSave = false, withShutdownHook = false)).use { store ->
            store.set(CodeWinsConfig::greeting, "Hello")
            store.saveImmediate()
        }
        val fileWins = explicit.readText()
        assertEquals("// mon accueil", lineBefore(fileWins, "greeting"))
        assertTrue(fileWins.contains("greeting: 'Hello'"))
    }

    @Test
    fun `le JSON strict ignore le commentaire, TOML aussi et écrit TomlComment`() {
        val json = newStorePath("strict.json")
        JsonFormat().encodeToPath(CommentedConfig.serializer(), CommentedConfig(), json)
        assertFalse(json.readText().contains("//"))
        assertEquals(CommentedConfig(), JsonFormat().decodeFromPath(CommentedConfig.serializer(), json))

        val toml = newStorePath("commented.toml")
        TomlFormat().encodeToPath(TomlCommented.serializer(), TomlCommented(), toml)
        val text = toml.readText()
        assertTrue(text.contains("# Le titre, par tomlkt"), text)
        assertFalse(text.contains("Ignoré en TOML"))
    }

    @Test
    fun `une racine sans classe et un descripteur fait main n'ont ni commentaire ni erreur`() {
        val map = newStorePath("map.json5")
        format.encodeToPath(MapSerializer(String.serializer(), Int.serializer()), mapOf("a" to 1), map)
        assertFalse(map.readText().contains("//"))

        val handMade = newStorePath("hand-made.json5")
        format.encodeToPath(HandMadeSerializer, HandMade("x"), handMade)
        assertFalse(handMade.readText().contains("//"))
        assertTrue(handMade.readText().contains("x: 'x'"))
    }

    @Test
    fun `l'écrivain public hors store écrit les commentaires aussi`() {
        val path = newStorePath("atomic.json5")

        format.encodeToPathAtomically(CommentedConfig.serializer(), CommentedConfig(), path)

        assertTrue(path.readText().startsWith("// La config du mod Demo"))
        assertEquals(9, commentLines(path.readText()))
    }

    private fun commentLines(text: String) = text.lines().count { it.trimStart().startsWith("//") }

    private fun lineBefore(text: String, key: String): String {
        val lines = text.lines()
        val index = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        assertTrue(index > 0, "la clé $key manque")
        return lines[index - 1].trim()
    }

    private fun preserve(data: CommentedConfig, path: Path, previousText: String?, policy: CommentPolicy = CommentPolicy.FILE_WINS) =
        format.encodeToPathPreserving(CommentedConfig.serializer(), data, path, previousText, policy)

    @Serializable
    @StoreComment("La config du mod Demo : éditez les valeurs, pas les clés")
    data class CommentedConfig(
        @StoreComment("Le message d'accueil") var greeting: String = "Bienvenue",
        @StoreComment("Entre 1 et 100", "Au-delà, la commande refuse") var maxHomes: Int = 3,
        var silent: Int = 0,
        @StoreComment("Les délais, en secondes") var delays: Delays = Delays(),
        @StoreComment("Par monde : le nom du monde vers sa règle") var perWorld: MutableMap<String, Int> = mutableMapOf("overworld" to 1),
        @StoreComment("Une première ligne\net une seconde, coupée par le code") var split: Boolean = true,
    )

    @Serializable
    data class Delays(@StoreComment("Avant la téléportation") var warmup: Int = 3, var cooldown: Int = 60)

    @Serializable
    @StoreConfiguration(withAutoSave = false, withShutdownHook = false, commentPolicy = CommentPolicy.CODE_WINS)
    data class CodeWinsConfig(@StoreComment("Le message d'accueil") var greeting: String = "Bienvenue")

    @Serializable
    data class TomlCommented(@TomlComment("Le titre, par tomlkt") @StoreComment("Ignoré en TOML") var title: String = "toml")

    data class HandMade(val x: String)

    /** Un sérialiseur à descripteur fait main : rien n'y déclare de commentaire, et rien ne doit casser. */
    private object HandMadeSerializer : KSerializer<HandMade> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HandMade") { element<String>("x") }
        override fun serialize(encoder: Encoder, value: HandMade) = encoder.encodeStructure(descriptor) { encodeStringElement(descriptor, 0, value.x) }
        override fun deserialize(decoder: Decoder): HandMade = throw UnsupportedOperationException("écriture seule")
    }
}
