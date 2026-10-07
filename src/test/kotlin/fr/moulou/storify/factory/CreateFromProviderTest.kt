// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.factory

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.UpdatePolicy
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.BareLeaf
import fr.moulou.storify.support.BareRoot
import fr.moulou.storify.support.BareRootSerializer
import fr.moulou.storify.support.BareRootValidator
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validation.ValidationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * La neuvième fabrique, `createFromProvider` (C-54) : le sérialiseur et le fournisseur des données initiales donnés en valeurs, aucune
 * annotation de classe lue, la même résolution que les huit autres pour le format et la config, l'explicite puis le repli.
 */
class CreateFromProviderTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    // ─── Les données initiales ───

    @Test
    fun `le fichier absent est créé depuis le fournisseur, appelé une fois`() {
        val path = newStorePath("bare.json")
        var calls = 0
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { calls++; BareRoot(name = "fourni", level = 4) }.use { store ->
            assertEquals("fourni", store.data.name)
            assertEquals(1, calls)
            assertTrue(path.readText().contains("\"fourni\"")) // le fichier initial, écrit par le sérialiseur donné
        }
    }

    @Test
    fun `le fichier présent est lu, le fournisseur n'est pas appelé`() {
        val path = newStorePath("bare.json")
        path.writeText("""{"name": "du fichier", "level": 7, "label": "feuille"}""")
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { error("the provider must not be called when the file exists") }.use { store ->
            assertEquals("du fichier", store.data.name)
            assertEquals(7, store.data.level)
            assertEquals("feuille", store.data.leaf.label)
        }
    }

    // ─── Le format ───

    @Test
    fun `sans format, l'extension du chemin choisit dans le registre`() {
        val path = newStorePath("bare.toml")
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { BareRoot() }.use { store ->
            assertTrue(store.format is TomlFormat)
            assertTrue(path.readText().contains("name = ")) // du TOML
        }
    }

    @Test
    fun `le format donné bat l'extension`() {
        val path = newStorePath("bare.toml")
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), format = JsonFormat(), config = noAutoSave) { BareRoot() }.use { store ->
            assertTrue(store.format is JsonFormat)
            assertTrue(path.readText().trimStart().startsWith("{")) // du JSON dans un .toml
        }
    }

    @Test
    fun `une extension inconnue sans format est refusée, avant tout fichier`() {
        val path = newStorePath("bare.dat")
        assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { BareRoot() }
        }
        assertFalse(Files.exists(path))
    }

    // ─── Le validator ───

    @Test
    fun `le validator donné tourne à l'ouverture, des défauts invalides n'écrivent rien`() {
        val path = newStorePath("bare.json")
        assertThrows(ValidationException::class.java) {
            StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave, validator = BareRootValidator()) { BareRoot(level = -1) }
        }
        assertFalse(Files.exists(path)) // C-06 : la validation d'abord, le fichier initial après
    }

    @Test
    fun `le validator donné tourne au rechargement, la mémoire reste intacte en échec`() {
        val path = newStorePath("bare.json")
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave, validator = BareRootValidator()) { BareRoot(level = 2) }.use { store ->
            path.writeText("""{"name": "bare", "level": -5, "label": "leaf"}""")
            assertThrows(ValidationException::class.java) { store.reloadFromFile() }
            assertEquals(2, store.data.level)
        }
    }

    // ─── Aucune annotation de classe lue ───

    @Test
    fun `une racine sans aucune annotation s'ouvre, se met à jour et se relit par le sérialiseur donné`() {
        val path = newStorePath("bare.json")
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { BareRoot() }.use { store ->
            store.set(BareRoot::level, 9)
            store.saveImmediate()
        }
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { error("the file exists") }.use { reloaded ->
            assertEquals(9, reloaded.data.level)
        }
    }

    @Test
    fun `une Map en racine, sans classe, se met à jour par transaction`() {
        val path = newStorePath("rules.json")
        StoreFactory.createFromProvider(serializer<MutableMap<String, Int>>(), path.toString(), config = noAutoSave) { mutableMapOf("max-homes" to 3) }.use { store ->
            store.transaction { this["delay"] = 5 }
            store.saveImmediate()
            assertEquals(mapOf("max-homes" to 3, "delay" to 5), store.data)
        }
        assertTrue(path.readText().contains("\"delay\""))
    }

    @Test
    fun `les annotations de classe d'une racine annotée sont ignorées, celle d'une propriété reste lue par le store`() {
        // AnnotatedData : @StorePath (un .json ailleurs), @StoreFileFormat(JSON), @StoreConfiguration(withAutoSave = false, SNAPSHOT), @StoreValidator (greeting non vide).
        val path = newStorePath("annotated.toml")
        StoreFactory.createFromProvider(AnnotatedData.serializer(), path.toString()) { AnnotatedData(greeting = "") }.use { store ->
            assertTrue(store.format is TomlFormat)                                         // l'extension, pas @StoreFileFormat
            assertEquals("", store.data.greeting)                                          // @StoreValidator n'a pas tourné
            assertEquals(UpdatePolicy.SKIP, store.config.defaultUpdatePolicy)              // StoreConfig(), pas @StoreConfiguration
            assertTrue(store.config.withAutoSave)
            assertEquals(UpdatePolicy.SHALLOW, store.getUpdatePolicy(AnnotatedData::uses)) // @StoreUpdatePolicy, que le store lit lui-même sur la classe réelle
        }
    }

    // ─── La copie profonde par le sérialiseur donné ───

    @Test
    fun `le rollback d'une transaction passe par le sérialiseur donné`() {
        val path = newStorePath("bare.json")
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = noAutoSave) { BareRoot(name = "avant") }.use { store ->
            assertThrows(IllegalStateException::class.java) {
                store.transaction { name = "pendant"; leaf.label = "touchée"; error("rollback") }
            }
            assertEquals("avant", store.data.name)
            assertEquals("leaf", store.data.leaf.label) // la copie de secours a traversé le substitut, feuille comprise
        }
    }

    // ─── La limite sous SNAPSHOT ───

    @Test
    fun `sous SNAPSHOT, une propriété dont le type n'a pas de sérialiseur échoue au premier update observé, la mémoire intacte`() {
        val path = newStorePath("bare.json")
        val snapshots = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
        StoreFactory.createFromProvider(BareRootSerializer, path.toString(), config = snapshots) { BareRoot() }.use { store ->
            store.set(BareRoot::leaf, BareLeaf("sans public")) // personne n'écoute : rien n'est copié (C-25)
            store.registerOnUpdate { }
            store.set(BareRoot::level, 2)                       // un Int est immuable : capturé tel quel, sans sérialiseur
            val failure = assertThrows(SerializationException::class.java) { store.set(BareRoot::leaf, BareLeaf("observée")) }
            assertTrue(failure.message!!.contains("BareLeaf"))
            assertEquals("sans public", store.data.leaf.label)  // l'avant se capture avant la mutation : rien n'a bougé
        }
    }
}
