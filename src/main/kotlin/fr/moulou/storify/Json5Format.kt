package fr.moulou.storify

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import li.songe.json5.Json5
import li.songe.json5.Json5EditConfig
import li.songe.json5.Json5EncoderConfig
import li.songe.json5.Json5Path
import li.songe.json5.putProperty
import li.songe.json5.remove
import li.songe.json5.set
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le format JSON5 (C-21) : le JSON des configs éditées à la main, commentaires, clés sans
 * guillemets, virgules traînantes et apostrophes compris.
 *
 * Le montage suit la conception de la brique `li.songe:json5` elle-même : le texte est du JSON5 de
 * bout en bout (parsé et écrit par [Json5]), et le [Json] de kotlinx ne sert que de moteur d'arbre
 * (data class vers `JsonElement` et retour), sans jamais produire de texte. L'API de la brique
 * étant entièrement texte, le fichier se lit entier : le créneau est la config, pas la donnée de
 * masse.
 *
 * Depuis C-26, la sauvegarde est préservante ([PreservingStoreFormat]) : le fichier existant est
 * réconcilié plutôt que réécrit : seules les valeurs changées se retouchent, les clés nouvelles
 * s'ajoutent, les disparues s'en vont avec leurs commentaires ; le style et les commentaires de
 * l'admin survivent, et un save sans changement laisse le fichier identique à l'octet. Limites
 * assumées : un tableau modifié se remplace entier (ses commentaires intérieurs meurent), et un
 * fichier cible absent ou invalide vaut encode à neuf.
 */
class Json5Format(
    private val json: Json = Json { encodeDefaults = true },
    private val encoderConfig: Json5EncoderConfig = Json5EncoderConfig(indent = "    ")
) : PreservingStoreFormat {

    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return json.decodeFromJsonElement(deserializer, Json5.parseToJsonElement(path.readText()))
    }

    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.writeText(Json5.encodeToString(json.encodeToJsonElement(serializer, data), encoderConfig))
    }

    override fun <DATA> encodeToPathPreserving(serializer: SerializationStrategy<DATA>, data: DATA, path: Path, previousText: String?) {
        path.parent?.createDirectories()
        val newElement = json.encodeToJsonElement(serializer, data)
        val reconciled = previousText?.let { reconcile(it, newElement) }
        path.writeText(reconciled ?: Json5.encodeToString(newElement, encoderConfig))
    }

    override fun fileExtension(): String = "json5"

    fun underlyingJson(): Json = json

    /** Le texte réconcilié (les retouches seules, commentaires et style préservés), ou null si l'existant est invalide : repli sur l'encode à neuf. */
    private fun reconcile(previousText: String, newElement: JsonElement): String? {
        var document = Json5.parseToDocument(previousText)
        if (!document.isValid) return null
        val edits = mutableListOf<ReconcileEdit>()
        diffInto(edits, document.toJsonElement(), newElement, Json5Path.Root)
        if (edits.isEmpty()) return previousText // rien n'a changé : pas un octet ne bouge
        val editConfig = Json5EditConfig(encoderConfig = encoderConfig)
        for (edit in edits) {
            document = when (edit) {
                is ReconcileEdit.Set -> document.set(edit.path, edit.value, editConfig)
                is ReconcileEdit.Put -> document.putProperty(edit.parent, edit.name, edit.value, editConfig)
                is ReconcileEdit.Remove -> document.remove(edit.path)
            }.document
        }
        return document.source
    }

    /** Le diff récursif : les objets se comparent clé à clé ; tout le reste (scalaires, tableaux, changements de type) se remplace en bloc. */
    private fun diffInto(edits: MutableList<ReconcileEdit>, old: JsonElement, new: JsonElement, path: Json5Path) {
        if (old == new) return
        if (old is JsonObject && new is JsonObject) {
            for (key in old.keys) if (key !in new) edits.add(ReconcileEdit.Remove(path[key]))
            for ((key, value) in new) {
                val previous = old[key]
                if (previous == null) edits.add(ReconcileEdit.Put(path, key, value)) else diffInto(edits, previous, value, path[key])
            }
        } else {
            edits.add(ReconcileEdit.Set(path, new))
        }
    }

    private sealed interface ReconcileEdit {
        data class Set(val path: Json5Path, val value: JsonElement) : ReconcileEdit
        data class Put(val parent: Json5Path, val name: String, val value: JsonElement) : ReconcileEdit
        data class Remove(val path: Json5Path) : ReconcileEdit
    }
}
