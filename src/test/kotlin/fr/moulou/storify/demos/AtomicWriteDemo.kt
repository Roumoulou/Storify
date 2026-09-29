// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.encodeToPathAtomically
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.utils.AtomicFiles
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.outputStream
import kotlin.io.path.readText

/*
 * La démo de C-34 : ce que l'écriture atomique protège, et ce qui manque hors d'un store. Un « crash au milieu de l'écriture » (disque plein,
 * processus tué, coupure) est simulé par un format qui écrit la moitié du fichier puis lève. Étape 1 : écrit directement par le format, le
 * fichier précédent est perdu, remplacé par un tronqué. Étape 2 : écrit par un store, le fichier précédent reste entier, parce que le store
 * écrit dans un temporaire voisin puis le déplace d'un coup (C-02). Étape 3 : depuis C-34, ce mécanisme est public, AtomicFiles.write et
 * encodeToPathAtomically, pour tout fichier écrit hors store (un export, une copie de secours, un catalogue généré).
 */

/** Un format qui tombe en panne au milieu de l'écriture : la moitié du JSON, puis une IOException. */
class HalfWayFormat : StoreFormat {
    private val json = JsonFormat()
    override fun fileExtension(): String = "json"
    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA = json.decodeFromPath(deserializer, path)
    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.outputStream().use { stream ->
            stream.write("{\n  \"name\": \"à moitié".toByteArray())
            throw IOException("disque plein au milieu de l'écriture")
        }
    }
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class AtomicWriteDemoTest {

    private fun goodFile(): Path = newStorePath("data.json").also { JsonFormat().encodeToPath(PlainData.serializer(), PlainData(name = "steve", count = 7), it) }

    private fun show(label: String, path: Path) = println("$label : ${path.readText().replace("\n", " ").replace(Regex("\\s+"), " ")}")

    @Test
    fun `étape 1, écrit directement par le format, le fichier précédent est perdu`() {
        val path = goodFile()
        show("avant", path)

        val failure = runCatching { HalfWayFormat().encodeToPath(PlainData.serializer(), PlainData(name = "alex"), path) }.exceptionOrNull()
        println("panne : ${failure?.message}")
        show("après", path) // la moitié d'un fichier : le précédent est parti, le nouveau n'est pas arrivé
        check(!path.readText().contains("steve"))
    }

    @Test
    fun `étape 2, écrit par un store, le fichier précédent reste entier`() {
        val path = goodFile()
        val store = StoreFactory.create<PlainData>(path.toString(), format = HalfWayFormat(), config = StoreConfig(withAutoSave = false))
        show("avant", path)

        store.set(PlainData::name, "alex")
        val failure = runCatching { store.saveImmediate() }.exceptionOrNull()
        println("panne : ${failure?.message}")
        show("après", path) // intact : la panne a frappé le temporaire, qui a été supprimé
        val leftovers = Files.list(path.parent).use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        println("temporaires restants : $leftovers")
        check(path.readText().contains("steve") && leftovers == 0L)
        runCatching { store.close() } // le store est resté dirty : la sauvegarde d'adieu retente, et échoue de même, le fichier toujours intact
        show("après close", path)
    }

    @Test
    fun `étape 3, depuis C-34, le même écrivain hors store, AtomicFiles et encodeToPathAtomically`() {
        val path = goodFile()
        show("avant", path)

        val failure = runCatching { AtomicFiles.write(path) { temp -> HalfWayFormat().encodeToPath(PlainData.serializer(), PlainData(name = "alex"), temp) } }.exceptionOrNull()
        println("panne : ${failure?.message}")
        show("après la panne", path) // intact, comme pour un store
        check(path.readText().contains("steve"))

        JsonFormat().encodeToPathAtomically(PlainData(name = "alex", count = 8), path) // l'écriture qui réussit, atomique elle aussi
        show("après l'export", path)
        check(path.readText().contains("alex"))
    }
}
