// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import kotlinx.serialization.SerializationException

/**
 * Une clé déclarée deux fois dans le même objet d'un fichier JSON ou JSON5 (C-50) : la cause de la `StoreDecodeException` que le format lève,
 * avec [key] la clé, [line] la ligne (à partir de 1) de sa seconde occurrence et [path] son chemin dans le fichier (C-55), la clé en dernier
 * segment, dans la grammaire d'[ErrorPath] ; le message le nomme, rendu par [ErrorPath.render].
 */
class DuplicateKeyException(val key: String, val line: Int, val path: List<PathSegment>) : SerializationException("Duplicate key '$key' at ${ErrorPath.render(path)}")

/**
 * Le scanner des clés en double d'un texte JSON strict (C-50) : les portées ouvertes en pile, les clés de chaque objet dans un ensemble, les
 * chaînes décodées de leurs échappements avant comparaison (`"ab"` et `"ab"` sont la même clé), la ligne comptée au passage. Chaque portée
 * retient ce qui l'a ouverte, la dernière clé lue dans l'objet parent ou le rang dans le tableau parent, compté à ses virgules, et le chemin
 * du doublon s'en construit (C-55), au doublon seulement. Un tableau ouvre une portée sans clés, et une chaîne n'est une clé que si le premier
 * caractère significatif qui la suit est `:`. Le texte est supposé être du JSON strict, sans commentaire ni clé nue : ce que le lecteur
 * tolérant accepte ne passe pas ici, et il n'y est pas soumis. Un texte mal formé n'est pas diagnostiqué : c'est l'affaire du décodeur, qui
 * vient avant.
 */
object JsonDuplicateKeys {

    /** Le premier doublon de [text], dans l'ordre du texte, ou `null`. */
    fun firstDuplicate(text: String): Duplicate? {
        val scopes = ArrayDeque<Scope>()
        var line = 1
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\n' -> { line++; i++ }
                '"' -> {
                    val (value, end) = readString(text, i + 1)
                    val keys = scopes.lastOrNull()?.keys
                    if (keys != null && isKey(text, end)) {
                        if (!keys.add(value)) return Duplicate(value, line, scopes.mapNotNull { it.opener } + PathSegment.Key(value))
                        scopes.last().lastKey = value
                    }
                    i = end
                }
                '{' -> { scopes.addLast(Scope(HashSet(), scopes.lastOrNull()?.childOpener())); i++ }
                '[' -> { scopes.addLast(Scope(null, scopes.lastOrNull()?.childOpener())); i++ }
                '}', ']' -> { scopes.removeLastOrNull(); i++ }
                ',' -> { scopes.lastOrNull()?.let { if (it.keys == null) it.rank++ }; i++ }
                else -> i++
            }
        }
        return null
    }

    /** Vrai si le premier caractère significatif à partir de [from] est `:` : la chaîne qui précède est une clé. */
    private fun isKey(text: String, from: Int): Boolean {
        var j = from
        while (j < text.length && text[j].isWhitespace()) j++
        return j < text.length && text[j] == ':'
    }

    /** La chaîne qui commence à [start] (après le guillemet ouvrant), décodée, et l'index qui suit son guillemet fermant. */
    private fun readString(text: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = start
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return out.toString() to i + 1
                '\\' -> {
                    when (val escaped = text.getOrNull(i + 1)) {
                        'u' -> {
                            val code = if (i + 6 <= text.length) text.substring(i + 2, i + 6).toIntOrNull(16) else null
                            if (code != null) out.append(code.toChar())
                            i += 6
                        }
                        'n' -> { out.append('\n'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        'r' -> { out.append('\r'); i += 2 }
                        'b' -> { out.append('\b'); i += 2 }
                        'f' -> { out.append('\u000C'); i += 2 }
                        null -> i++
                        else -> { out.append(escaped); i += 2 } // les échappements \" \\ et \/
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString() to text.length
    }

    /** Une clé déclarée deux fois dans le même objet, la ligne (à partir de 1) de sa seconde occurrence et son chemin, la clé en dernier segment (C-55). */
    data class Duplicate(val key: String, val line: Int, val path: List<PathSegment>)

    /** Une portée ouverte : un objet, ses clés dans [keys], ou un tableau, `null` ; et le segment sous lequel elle s'est ouverte, `null` à la racine. */
    private class Scope(val keys: HashSet<String>?, val opener: PathSegment?) {
        /** La dernière clé lue dans un objet : celle sous laquelle s'ouvre l'enfant qui la suit. */
        var lastKey: String? = null

        /** Le rang de l'élément courant d'un tableau, compté à ses virgules. */
        var rank = 0

        /** Le segment qu'un enfant ouvert maintenant reçoit : la dernière clé lue, ou le rang dans le tableau. La clé ne manque que dans un texte mal formé, que le décodeur a refusé avant. */
        fun childOpener(): PathSegment = if (keys != null) PathSegment.Key(lastKey.orEmpty()) else PathSegment.Index(rank)
    }
}
