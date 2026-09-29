// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

/** Un segment du chemin d'une [ValidationError] : une clé d'objet ou de map, ou l'index d'un tableau (C-32). */
sealed interface PathSegment {
    data class Key(val name: String) : PathSegment
    data class Index(val index: Int) : PathSegment
}

/**
 * La grammaire des chemins d'erreur (C-32) : `a.b` pour une propriété, `a[3]` pour un index, `a[steve]` ou `a["steve"]` pour une clé de map,
 * les points permis entre crochets (`homes[my.home]`). Le premier segment du `path` d'une erreur est le nom de la classe racine : il ne désigne
 * rien dans le fichier.
 */
object ErrorPath {

    /** Les segments d'une erreur : son `path` sans la classe racine, puis son `field`. */
    fun segments(error: ValidationError): List<PathSegment> {
        val afterRoot = error.path.substringAfter('.', "")
        val raw = listOf(afterRoot, error.field.orEmpty()).filter { it.isNotEmpty() }.joinToString(".")
        return parse(raw)
    }

    /** Les segments d'un chemin brut ; un chemin mal formé (crochet non fermé, crochets vides) lève [IllegalArgumentException]. */
    fun parse(raw: String): List<PathSegment> {
        val segments = mutableListOf<PathSegment>()
        val key = StringBuilder()
        fun flushKey() {
            if (key.isNotEmpty()) {
                segments.add(PathSegment.Key(key.toString()))
                key.clear()
            }
        }
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                '.' -> flushKey()
                '[' -> {
                    flushKey()
                    val close = raw.indexOf(']', i + 1)
                    require(close > i) { "unclosed bracket in path '$raw'" }
                    segments.add(bracketSegment(raw.substring(i + 1, close), raw))
                    i = close
                }
                else -> key.append(c)
            }
            i++
        }
        flushKey()
        return segments
    }

    private fun bracketSegment(inside: String, raw: String): PathSegment {
        require(inside.isNotEmpty()) { "empty brackets in path '$raw'" }
        val quoted = inside.length >= 2 && ((inside.first() == '"' && inside.last() == '"') || (inside.first() == '\'' && inside.last() == '\''))
        if (quoted) return PathSegment.Key(inside.substring(1, inside.length - 1))
        return if (inside.all { it.isDigit() }) PathSegment.Index(inside.toInt()) else PathSegment.Key(inside)
    }
}

/**
 * Retrouve, dans les lignes d'un fichier, celle que désigne un chemin d'erreur (C-32). Un format qui sait le faire le rend par
 * `StoreFormat.lineLocator()` ; à défaut, les erreurs restent sans ligne.
 */
interface ErrorLineLocator {
    /** La ligne (à partir de 1) que désignent les [segments], ou `null` si le chemin ne se retrouve pas. */
    fun lineOf(lines: List<String>, segments: List<PathSegment>): Int?
}

/**
 * Le localisateur de la famille JSON (JSON et JSON5) : le fichier est parcouru ligne à ligne en suivant la profondeur des accolades et des
 * crochets, hors chaînes (`"` et `'`) et hors commentaires (`//` et `/* */`). Une clé se reconnaît en tête de ligne sous ses trois graphies,
 * `"clé"`, `'clé'` ou `clé` nue ; un index compte les éléments d'un tableau, un par ligne. Une valeur qui tient sur la ligne de sa clé (un
 * scalaire, un tableau ou un objet en ligne) rend cette ligne pour tout ce qui la suit : au mieux, pas au plus juste. Limite assumée : une
 * clé par ligne, le style qu'écrit Storify et qu'un fichier édité à la main garde presque toujours.
 */
object JsonLineLocator : ErrorLineLocator {

    override fun lineOf(lines: List<String>, segments: List<PathSegment>): Int? {
        if (segments.isEmpty()) return null
        val scanner = LineScanner()
        var segmentIndex = 0
        var depth = 0
        var targetDepth = 1
        var elementCount = 0
        for ((lineIndex, line) in lines.withIndex()) {
            val depthBefore = depth
            val (depthAfter, content) = scanner.scan(line, depthBefore)
            depth = depthAfter
            if (content.isEmpty() || depthBefore != targetDepth) continue
            val matched = when (val segment = segments[segmentIndex]) {
                is PathSegment.Key -> declaresKey(content, segment.name)
                is PathSegment.Index -> startsElement(content) && elementCount++ == segment.index
            }
            if (!matched) continue
            segmentIndex++
            val lineNumber = lineIndex + 1
            if (segmentIndex == segments.size) return lineNumber
            if (depthAfter == depthBefore) return lineNumber // la valeur tient sur la ligne : au mieux, cette ligne
            targetDepth = depthBefore + 1
            elementCount = 0
        }
        return null
    }

    private fun declaresKey(content: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("""^(?:"$escaped"|'$escaped'|$escaped)\s*:""").containsMatchIn(content)
    }

    private fun startsElement(content: String): Boolean = content.first() !in "]},"

    /** Le compte de profondeur d'une ligne, aveugle aux chaînes et aux commentaires ; un commentaire bloc survit d'une ligne à l'autre. */
    private class LineScanner {
        private var inBlockComment = false

        /** Rend la profondeur après la ligne, et son contenu significatif (dès le premier caractère hors espace et hors commentaire, vide sinon). */
        fun scan(line: String, depthBefore: Int): Pair<Int, String> {
            var depth = depthBefore
            var firstSignificant = -1
            var inString: Char? = null
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inBlockComment -> if (c == '*' && line.getOrNull(i + 1) == '/') { inBlockComment = false; i++ }
                    inString != null -> if (c == '\\') i++ else if (c == inString) inString = null
                    c == '/' && line.getOrNull(i + 1) == '/' -> break
                    c == '/' && line.getOrNull(i + 1) == '*' -> { inBlockComment = true; i++ }
                    c.isWhitespace() -> {}
                    else -> {
                        if (firstSignificant < 0) firstSignificant = i
                        when (c) {
                            '"', '\'' -> inString = c
                            '{', '[' -> depth++
                            '}', ']' -> depth--
                        }
                    }
                }
                i++
            }
            return depth to (if (firstSignificant < 0) "" else line.substring(firstSignificant).trimEnd())
        }
    }
}
