// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import fr.moulou.storify.utils.DeepCopier
import fr.moulou.storify.utils.JsonTreeCopier
import fr.moulou.storify.utils.withoutUtf8Bom
import fr.moulou.storify.validation.DuplicateKeyException
import fr.moulou.storify.validation.ErrorLineLocator
import fr.moulou.storify.validation.JsonDuplicateKeys
import fr.moulou.storify.validation.JsonLineLocator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import li.songe.json5.Json5
import li.songe.json5.Json5Array
import li.songe.json5.Json5EditConfig
import li.songe.json5.Json5EncoderConfig
import li.songe.json5.Json5Object
import li.songe.json5.Json5Path
import li.songe.json5.Json5Value
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
 * La sauvegarde est préservante ([PreservingStoreFormat], C-26) : le fichier existant est
 * réconcilié plutôt que réécrit : seules les valeurs changées se retouchent, les clés nouvelles
 * s'ajoutent, les disparues s'en vont avec leurs commentaires ; le style et les commentaires de
 * l'admin survivent, et un save sans changement laisse le fichier identique à l'octet. Limites
 * assumées : un tableau modifié se remplace entier (ses commentaires intérieurs meurent), et un
 * fichier cible absent ou invalide vaut encode à neuf.
 *
 * Le pont par défaut garde `encodeDefaults` (un fichier qui porte tous ses champs) et
 * `allowSpecialFloatingPointValues` (C-42) : `NaN`, `Infinity` et `-Infinity`, du JSON5 valide,
 * s'écrivent et se relisent, comme en JSON (C-36) ; les refuser ferait échouer chaque sauvegarde du
 * store, loin du code qui a produit la valeur. Le constructeur accepte tout `Json` : celui d'un
 * consommateur est pris tel quel.
 *
 * Une clé déclarée deux fois dans le même objet est refusée (C-50), par [DuplicateKeyException], que le
 * store rend en [StoreDecodeException] avec la ligne de la seconde occurrence : le texte est parsé en
 * document, dont l'AST garde les deux membres (noms décodés de leurs échappements et de leurs
 * guillemets), et parcouru après le contrôle de syntaxe, avant la conversion en arbre, où la brique ne
 * garderait que la dernière valeur.
 */
class Json5Format(
    private val json: Json = Json {
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    },
    private val encoderConfig: Json5EncoderConfig = Json5EncoderConfig(indent = "    ")
) : PreservingStoreFormat {

    /** Le copieur profond des stores JSON5 : l'arbre JSON du pont kotlinx, le même qui décode et encode les fichiers (C-29). */
    private val copier = JsonTreeCopier(json)

    override fun fileExtension(): String = "json5"

    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        val document = Json5.parseToDocument(path.readText().withoutUtf8Bom())
        val element = document.toJsonElement() // un texte mal formé lève ici, avant toute recherche de doublon
        document.root?.let { root -> firstDuplicate(root, document.source)?.let { throw DuplicateKeyException(it.key, it.line) } }
        return json.decodeFromJsonElement(deserializer, element)
    }

    /** Le premier doublon de l'AST, dans l'ordre du texte (C-50) : les noms décodés comparés objet par objet, la ligne comptée depuis l'offset. */
    private fun firstDuplicate(value: Json5Value, source: String): JsonDuplicateKeys.Duplicate? {
        when (value) {
            is Json5Object -> {
                val seen = HashSet<String>()
                for (property in value.properties) {
                    val name = property.name.value
                    if (!seen.add(name)) return JsonDuplicateKeys.Duplicate(name, source.substring(0, property.name.range.start).count { it == '\n' } + 1)
                    firstDuplicate(property.value, source)?.let { return it }
                }
            }
            is Json5Array -> for (element in value.elements) firstDuplicate(element, source)?.let { return it }
            else -> {}
        }
        return null
    }

    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.writeText(Json5.encodeToString(json.encodeToJsonElement(serializer, data), encoderConfig))
    }

    override fun <DATA> encodeToPathPreserving(serializer: SerializationStrategy<DATA>, data: DATA, path: Path, previousText: String?) {
        path.parent?.createDirectories()
        val newElement = json.encodeToJsonElement(serializer, data)
        val reconciled = previousText?.withoutUtf8Bom()?.let { reconcile(it, newElement) }
        path.writeText(reconciled ?: Json5.encodeToString(newElement, encoderConfig))
    }

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

    override fun deepCopier(): DeepCopier = copier

    override fun lineLocator(): ErrorLineLocator = JsonLineLocator

    fun underlyingJson(): Json = json

    private sealed interface ReconcileEdit {
        data class Set(val path: Json5Path, val value: JsonElement) : ReconcileEdit
        data class Put(val parent: Json5Path, val name: String, val value: JsonElement) : ReconcileEdit
        data class Remove(val path: Json5Path) : ReconcileEdit
    }
}
