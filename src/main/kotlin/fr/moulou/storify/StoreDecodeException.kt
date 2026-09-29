// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import java.nio.file.Path

/**
 * Un fichier que Storify n'a pas pu lire ou décoder (C-33) : à l'ouverture d'un store, au rechargement, dans `validateFile`, pour le sidecar
 * meta ou pour la ressource copiée par `createFromResource`. Le message porte le chemin, le format, la ligne quand elle se lit dans le
 * message du parseur, puis ce message entier ; la [cause] est l'exception d'origine, celle du parseur ou de la lecture.
 */
class StoreDecodeException(
    val path: Path,
    val format: StoreFormat,
    cause: Throwable,
    /** La ligne fautive, au mieux : l'offset de kotlinx ou l'index de json5 converti en ligne, le `(L2)` de tomlkt tel quel, `null` sinon. */
    val line: Int? = lineOf(path, cause),
) : StorifyException(buildMessage(path, format, line, cause), cause) {

    private companion object {
        fun buildMessage(path: Path, format: StoreFormat, line: Int?, cause: Throwable): String {
            val where = if (line != null) " at line $line" else ""
            return "[Storify] Cannot decode '$path' (${format::class.simpleName})$where: ${cause.message ?: cause::class.simpleName}"
        }

        /** Une heuristique sur le message du parseur : un offset ou un index en caractères devient une ligne en comptant les retours à la ligne du fichier jusque-là. */
        fun lineOf(path: Path, cause: Throwable): Int? {
            val message = cause.message ?: return null
            Regex("""\(L(\d+)\)""").find(message)?.let { return it.groupValues[1].toInt() }
            val offset = Regex("""\b(?:offset|index) (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val text = runCatching { path.toFile().readText() }.getOrNull() ?: return null
            if (offset > text.length) return null
            return text.substring(0, offset).count { it == '\n' } + 1
        }
    }
}
