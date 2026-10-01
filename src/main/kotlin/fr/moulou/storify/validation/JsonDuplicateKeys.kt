// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import kotlinx.serialization.SerializationException

/**
 * Une clé déclarée deux fois dans le même objet d'un fichier JSON ou JSON5 (C-50) : la cause de la `StoreDecodeException` que le format lève,
 * avec [key] la clé et [line] la ligne (à partir de 1) de sa seconde occurrence.
 */
class DuplicateKeyException(val key: String, val line: Int) : SerializationException("Duplicate key '$key'")

/**
 * Le scanner des clés en double d'un texte JSON strict (C-50) : les objets ouverts en pile, les clés de chacun dans un ensemble, les chaînes
 * décodées de leurs échappements avant comparaison (`"ab"` et `"ab"` sont la même clé), la ligne comptée au passage. Un tableau ouvre une
 * portée sans clés, et une chaîne n'est une clé que si le premier caractère significatif qui la suit est `:`. Le texte est supposé être du
 * JSON strict, sans commentaire ni clé nue : ce que le lecteur tolérant accepte ne passe pas ici, et il n'y est pas soumis. Un texte mal
 * formé n'est pas diagnostiqué : c'est l'affaire du décodeur, qui vient avant.
 */
object JsonDuplicateKeys {

    /** Le premier doublon de [text], dans l'ordre du texte, ou `null`. */
    fun firstDuplicate(text: String): Duplicate? {
        val scopes = ArrayDeque<HashSet<String>?>() // un ensemble par objet ouvert, null pour un tableau
        var line = 1
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\n' -> { line++; i++ }
                '"' -> {
                    val (value, end) = readString(text, i + 1)
                    val scope = scopes.lastOrNull()
                    if (scope != null) {
                        var j = end
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && text[j] == ':' && !scope.add(value)) return Duplicate(value, line)
                    }
                    i = end
                }
                '{' -> { scopes.addLast(HashSet()); i++ }
                '[' -> { scopes.addLast(null); i++ }
                '}', ']' -> { scopes.removeLastOrNull(); i++ }
                else -> i++
            }
        }
        return null
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

    /** Une clé déclarée deux fois dans le même objet, et la ligne (à partir de 1) de sa seconde occurrence. */
    data class Duplicate(val key: String, val line: Int)
}
