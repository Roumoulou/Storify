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
import fr.moulou.storify.validation.PathSegment
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import fr.moulou.storify.internal.json5.Json5
import fr.moulou.storify.internal.json5.Json5Array
import fr.moulou.storify.internal.json5.Json5Comment
import fr.moulou.storify.internal.json5.Json5CommentKind
import fr.moulou.storify.internal.json5.Json5CommentPlacement
import fr.moulou.storify.internal.json5.Json5Document
import fr.moulou.storify.internal.json5.Json5EditConfig
import fr.moulou.storify.internal.json5.Json5EncoderConfig
import fr.moulou.storify.internal.json5.Json5Object
import fr.moulou.storify.internal.json5.Json5Path
import fr.moulou.storify.internal.json5.Json5PathSegment
import fr.moulou.storify.internal.json5.Json5Value
import fr.moulou.storify.internal.json5.addCommentBefore
import fr.moulou.storify.internal.json5.putProperty
import fr.moulou.storify.internal.json5.remove
import fr.moulou.storify.internal.json5.resolve
import fr.moulou.storify.internal.json5.set
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le format JSON5 (C-21) : le JSON des configs éditées à la main, commentaires, clés sans
 * guillemets, virgules traînantes et apostrophes compris.
 *
 * Le montage suit la conception de la brique kotlin-json5 elle-même (`li.songe:json5` 0.8.0, copiée dans
 * les sources de Storify sous `internal.json5` depuis C-65, un jar de moins pour un mod et un paquet qui
 * n'en heurte aucun autre) : le texte est du JSON5 de bout en bout (parsé et écrit par [Json5]), et le [Json] de
 * kotlinx ne sert que de moteur d'arbre (data class vers `JsonElement` et retour), sans jamais produire
 * de texte. L'API de la brique étant entièrement texte, le fichier se lit entier : le créneau est la
 * config, pas la donnée de masse.
 *
 * La sauvegarde est préservante ([PreservingStoreFormat], C-26) : le fichier existant est
 * réconcilié plutôt que réécrit : seules les valeurs changées se retouchent, les clés nouvelles
 * s'ajoutent, les disparues s'en vont avec leurs commentaires ; le style et les commentaires de
 * l'admin survivent, et un save sans changement laisse le fichier identique à l'octet. Limites
 * assumées : un tableau modifié se remplace entier (ses commentaires intérieurs meurent), et un
 * fichier cible absent ou invalide vaut encode à neuf.
 *
 * Les commentaires du code ([StoreComment], C-64) se posent au-dessus de leurs clés, un `//` par
 * ligne, et en tête du fichier pour la racine : toujours dans un fichier qui naît ; dans un fichier
 * existant, sur les seules clés que la réconciliation ajoute ([CommentPolicy.FILE_WINS], l'admin
 * gagne), ou sur toutes les clés commentées, dont les commentaires de tête sont remplacés
 * ([CommentPolicy.CODE_WINS]). Une liste, une map ou un type polymorphe ne se descendent pas.
 *
 * Le pont par défaut garde `encodeDefaults` (un fichier qui porte tous ses champs) et
 * `allowSpecialFloatingPointValues` (C-42) : `NaN`, `Infinity` et `-Infinity`, du JSON5 valide,
 * s'écrivent et se relisent, comme en JSON (C-36) ; les refuser ferait échouer chaque sauvegarde du
 * store, loin du code qui a produit la valeur. Le constructeur accepte tout `Json` : celui d'un
 * consommateur est pris tel quel.
 *
 * Une clé déclarée deux fois dans le même objet est refusée (C-50), par [DuplicateKeyException], que le
 * store rend en [StoreDecodeException] avec la ligne de la seconde occurrence et le chemin du doublon
 * (C-55) : le texte est parsé en document, dont l'AST garde les deux membres (noms décodés de leurs
 * échappements et de leurs guillemets), et parcouru après le contrôle de syntaxe, avant la conversion
 * en arbre, où la brique ne garderait que la dernière valeur ; le chemin s'empile en descendant l'AST.
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
        document.root?.let { root -> firstDuplicate(root, document.source, ArrayDeque())?.let { throw DuplicateKeyException(it.key, it.line, it.path) } }
        return json.decodeFromJsonElement(deserializer, element)
    }

    /**
     * Le premier doublon de l'AST, dans l'ordre du texte (C-50) : les noms décodés comparés objet par objet, la ligne comptée depuis l'offset, et le
     * chemin empilé dans [path] en descendant, la clé ou le rang de chaque enfant visité (C-55).
     */
    private fun firstDuplicate(value: Json5Value, source: String, path: ArrayDeque<PathSegment>): JsonDuplicateKeys.Duplicate? {
        when (value) {
            is Json5Object -> {
                val seen = HashSet<String>()
                for (property in value.properties) {
                    val name = property.name.value
                    path.addLast(PathSegment.Key(name))
                    if (!seen.add(name)) return JsonDuplicateKeys.Duplicate(name, source.substring(0, property.name.range.start).count { it == '\n' } + 1, path.toList())
                    firstDuplicate(property.value, source, path)?.let { return it }
                    path.removeLast()
                }
            }
            is Json5Array -> for ((index, element) in value.elements.withIndex()) {
                path.addLast(PathSegment.Index(index))
                firstDuplicate(element, source, path)?.let { return it }
                path.removeLast()
            }
            else -> {}
        }
        return null
    }

    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.writeText(encodeNew(json.encodeToJsonElement(serializer, data), StoreComments.collect(serializer.descriptor)))
    }

    override fun <DATA> encodeToPathPreserving(serializer: SerializationStrategy<DATA>, data: DATA, path: Path, previousText: String?) =
        encodeToPathPreserving(serializer, data, path, previousText, CommentPolicy.FILE_WINS)

    override fun <DATA> encodeToPathPreserving(serializer: SerializationStrategy<DATA>, data: DATA, path: Path, previousText: String?, commentPolicy: CommentPolicy) {
        path.parent?.createDirectories()
        val newElement = json.encodeToJsonElement(serializer, data)
        val comments = StoreComments.collect(serializer.descriptor)
        val reconciled = previousText?.withoutUtf8Bom()?.let { reconcile(it, newElement, comments, commentPolicy) }
        path.writeText(reconciled ?: encodeNew(newElement, comments))
    }

    /** Le texte d'un fichier neuf : l'encode à neuf, puis les commentaires du code au-dessus de chaque clé qu'ils visent (C-64). */
    private fun encodeNew(element: JsonElement, comments: List<StoreComments.Entry>): String {
        val text = Json5.encodeToString(element, encoderConfig)
        if (comments.isEmpty()) return text
        return withComments(Json5.parseToDocument(text), comments, replace = false).source
    }

    /**
     * Le texte réconcilié (les retouches seules, commentaires et style préservés), ou null si l'existant est invalide : repli sur l'encode à neuf.
     * Les commentaires du code suivent [commentPolicy] : sous [CommentPolicy.FILE_WINS], les seules clés que les retouches créent les reçoivent, et
     * un save sans retouche ne bouge pas d'un octet ; sous [CommentPolicy.CODE_WINS], toutes les clés commentées, à chaque save (C-64).
     */
    private fun reconcile(previousText: String, newElement: JsonElement, comments: List<StoreComments.Entry>, commentPolicy: CommentPolicy): String? {
        var document = Json5.parseToDocument(previousText)
        if (!document.isValid) return null
        val edits = mutableListOf<ReconcileEdit>()
        diffInto(edits, document.toJsonElement(), newElement, Json5Path.Root)
        if (edits.isEmpty() && commentPolicy == CommentPolicy.FILE_WINS) return previousText // rien n'a changé : pas un octet ne bouge
        val editConfig = Json5EditConfig(encoderConfig = encoderConfig)
        for (edit in edits) {
            document = when (edit) {
                is ReconcileEdit.Set -> document.set(edit.path, edit.value, editConfig)
                is ReconcileEdit.Put -> document.putProperty(edit.parent, edit.name, edit.value, editConfig)
                is ReconcileEdit.Remove -> document.remove(edit.path)
            }.document
        }
        val commented = when (commentPolicy) {
            CommentPolicy.FILE_WINS -> comments.filter { entry -> edits.any { it.creates(entry.keys) } }
            CommentPolicy.CODE_WINS -> comments
        }
        return withComments(document, commented, replace = commentPolicy == CommentPolicy.CODE_WINS).source
    }

    /**
     * Pose les commentaires de [comments] dans [start], au-dessus de la clé de chaque chemin qui existe dans le document, un `//` par ligne dans
     * l'ordre du code, avant le `{` pour la racine ; avec [replace], les commentaires de tête que la clé portait s'en vont d'abord.
     */
    private fun withComments(start: Json5Document, comments: List<StoreComments.Entry>, replace: Boolean): Json5Document {
        val editConfig = Json5EditConfig(encoderConfig = encoderConfig)
        var document = start
        for (entry in comments) {
            val path = entry.keys.fold(Json5Path.Root) { parent, key -> parent[key] }
            if (document.resolve(path) == null) continue // une clé que le fichier n'écrit pas : rien à commenter
            if (replace) {
                while (true) {
                    val target = document.resolve(path)?.target ?: break
                    val leading = document.commentsOf(target, Json5CommentPlacement.Leading).firstOrNull() ?: break
                    document = document.withoutComment(leading)
                }
            }
            for (line in entry.lines) document = document.addCommentBefore(path, line, Json5CommentKind.Line, editConfig).document
        }
        return document
    }

    /** Le document sans [comment] ni l'indentation qui le précède, ni sa fin de ligne quand il occupait la ligne entière, pour ne pas laisser de ligne vide. */
    private fun Json5Document.withoutComment(comment: Json5Comment): Json5Document {
        var start = comment.range.start
        while (start > 0 && (source[start - 1] == ' ' || source[start - 1] == '\t')) start--
        var end = comment.range.end
        if (start == 0 || source[start - 1] == '\n') {
            if (source.startsWith("\r\n", end)) end += 2 else if (end < source.length && source[end] == '\n') end++
        }
        return Json5.parseToDocument(source.substring(0, start) + source.substring(end))
    }

    /** Vrai quand la retouche crée le nœud du chemin [keys] : une clé ajoutée sur ce chemin ou au-dessus, ou un sous-arbre réécrit au-dessus. */
    private fun ReconcileEdit.creates(keys: List<String>): Boolean {
        val created = when (this) {
            is ReconcileEdit.Put -> parent.segments + Json5PathSegment.Property(name)
            is ReconcileEdit.Set -> if (path.segments.size < keys.size) path.segments else return false
            is ReconcileEdit.Remove -> return false
        }
        return created.size <= keys.size && keys.take(created.size).map { Json5PathSegment.Property(it) } == created
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
