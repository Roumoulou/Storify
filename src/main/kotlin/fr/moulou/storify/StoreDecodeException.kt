// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import fr.moulou.storify.validation.DuplicateKeyException
import fr.moulou.storify.validation.ErrorPath
import fr.moulou.storify.validation.PathSegment
import java.nio.file.Path

/**
 * Un fichier que Storify n'a pas pu lire ou décoder (C-33) : à l'ouverture d'un store, au rechargement, dans `validateFile`, pour le sidecar
 * meta ou pour la ressource copiée par `createFromResource`. Le message porte le chemin, le format, la ligne quand elle se retrouve, puis le
 * message du parseur entier ; la [cause] est l'exception d'origine, celle du parseur ou de la lecture.
 */
class StoreDecodeException(
    val path: Path,
    val format: StoreFormat,
    cause: Throwable,
    /**
     * Le chemin de la valeur fautive, dans la grammaire des erreurs de validation ([PathSegment], C-56) : celui que porte une
     * [DuplicateKeyException], la clé en dernier segment (C-55), sinon celui que le message du parseur nomme (« at path $.pets['rex'].mood »,
     * kotlinx) ; vide sinon, et quand la faute est à la racine.
     */
    val valuePath: List<PathSegment> = valuePathOf(cause),
    /**
     * La ligne fautive, au mieux : celle que porte une [DuplicateKeyException] (C-50), le `(L2)` de tomlkt tel quel, l'offset de kotlinx ou
     * l'index de json5 converti en ligne ; sinon la ligne de [valuePath] par le localisateur du format, puis, quand le parseur a atteint la
     * fin du fichier, la ligne où il s'arrête (C-56) ; `null` quand rien ne la donne.
     */
    val line: Int? = lineOf(path, format, cause, valuePath),
) : StorifyException(buildMessage(path, format, line, cause), cause) {

    private companion object {
        /** Les deux graphies de kotlinx : « at path $.mood » et « at path: $ ». */
        val pathInMessage = Regex("""\bat path:? \$\.?(\S*)""")

        fun buildMessage(path: Path, format: StoreFormat, line: Int?, cause: Throwable): String {
            val where = if (line != null) " at line $line" else ""
            return "[Storify] Cannot decode '$path' (${format::class.simpleName})$where: ${cause.message ?: cause::class.simpleName}"
        }

        /** Le chemin d'un doublon, typé (C-55) ; sinon celui que le message du parseur nomme, dans la grammaire d'[ErrorPath] ; un chemin illisible rend la liste vide, jamais une faute dans la faute. */
        fun valuePathOf(cause: Throwable): List<PathSegment> {
            if (cause is DuplicateKeyException) return cause.path
            val raw = cause.message?.let { pathInMessage.find(it) }?.groupValues?.get(1) ?: return emptyList()
            return runCatching { ErrorPath.parse(raw) }.getOrDefault(emptyList())
        }

        /**
         * La ligne d'un doublon, sinon une heuristique sur le message du parseur : un `(L<n>)` tel quel, un offset ou un index en caractères
         * converti en ligne en comptant les retours à la ligne du fichier jusque-là ; sinon la ligne du chemin nommé, par le localisateur du
         * format (C-56) ; sinon, pour une fin de fichier atteinte (`'EOF'` dans le message), la ligne où le fichier s'arrête, celle qu'un
         * éditeur montre à sa fin, 1 pour un fichier vide.
         */
        fun lineOf(path: Path, format: StoreFormat, cause: Throwable, valuePath: List<PathSegment>): Int? {
            if (cause is DuplicateKeyException) return cause.line
            val message = cause.message ?: return null
            Regex("""\(L(\d+)\)""").find(message)?.let { return it.groupValues[1].toInt() }
            val text = runCatching { path.toFile().readText() }.getOrNull() ?: return null
            Regex("""\b(?:offset|index) (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { offset ->
                return if (offset > text.length) null else text.substring(0, offset).count { it == '\n' } + 1
            }
            if (valuePath.isNotEmpty()) format.lineLocator()?.lineOf(text.lines(), valuePath)?.let { return it }
            if ("'EOF'" in message) return text.count { it == '\n' } + 1
            return null
        }
    }
}
