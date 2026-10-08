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
 * rien dans le fichier. [render] écrit un chemin dans cette grammaire, [parse] le lit.
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

    /**
     * Le texte d'un chemin, le miroir de [parse] (C-55) : les clés jointes par un point, un index entre crochets, et entre crochets et
     * guillemets une clé vide ou qui porte un point ou un crochet (`homes["my.home"]`), pour qu'un chemin rendu se relise en lui-même, une clé
     * en chiffres comprise (`groups.123`). Seule une clé qui porte un crochet fermant ne se relit pas, parce que [parse] s'arrête au premier.
     */
    fun render(segments: List<PathSegment>): String = buildString {
        for (segment in segments) {
            when (segment) {
                is PathSegment.Index -> append('[').append(segment.index).append(']')
                is PathSegment.Key -> {
                    val plain = segment.name.isNotEmpty() && segment.name.none { it == '.' || it == '[' || it == ']' }
                    if (plain) {
                        if (isNotEmpty()) append('.')
                        append(segment.name)
                    } else {
                        append("[\"").append(segment.name).append("\"]")
                    }
                }
            }
        }
    }
}

/**
 * Retrouve, dans les lignes d'un fichier, celle que désigne un chemin d'erreur (C-32). Un format qui sait le faire le rend par
 * `StoreFormat.lineLocator()` ; à défaut, les erreurs restent sans ligne.
 */
interface ErrorLineLocator {
    /**
     * La ligne (à partir de 1) que désignent les [segments] ; quand le fichier n'écrit pas le chemin entier, au mieux celle de son plus proche
     * ancêtre écrit (C-53) ; `null` si rien du chemin ne se retrouve.
     */
    fun lineOf(lines: List<String>, segments: List<PathSegment>): Int?
}

/**
 * Le localisateur de la famille JSON (JSON et JSON5) : le fichier est parcouru ligne à ligne en suivant la profondeur des accolades et des
 * crochets, hors chaînes (`"` et `'`) et hors commentaires (`//` et `/* */`). Une clé se reconnaît en tête de ligne sous ses trois graphies,
 * `"clé"`, `'clé'` ou `clé` nue ; un index compte les éléments d'un tableau, un par ligne. Un segment ne se cherche que dans son parent
 * (C-53) : quand le parent se referme sans le porter, ou que sa valeur tient sur la ligne de sa clé (un scalaire, un tableau ou un objet en
 * ligne), la ligne rendue est celle du dernier segment retrouvé, au mieux et pas au plus juste. Limite assumée : une clé ou un élément par
 * ligne, le style qu'écrit Storify et qu'un fichier édité à la main garde presque toujours ; ce qui s'en écarte (plusieurs éléments sur une
 * ligne, un objet ouvert sur la ligne de sa clé de tableau) rend la ligne de l'ancêtre, ou celle d'un élément voisin dans un tableau qui mêle
 * les deux styles.
 */
object JsonLineLocator : ErrorLineLocator {

    override fun lineOf(lines: List<String>, segments: List<PathSegment>): Int? {
        if (segments.isEmpty()) return null
        val scanner = LineScanner()
        var segmentIndex = 0
        var depth = 0
        var targetDepth = 1
        var elementCount = 0
        var foundLine: Int? = null // la ligne du dernier segment retrouvé
        for ((lineIndex, line) in lines.withIndex()) {
            val depthBefore = depth
            val scan = scanner.scan(line, depthBefore)
            depth = scan.depthAfter
            val matched = scan.content.isNotEmpty() && depthBefore == targetDepth && when (val segment = segments[segmentIndex]) {
                is PathSegment.Key -> declaresKey(scan.content, segment.name)
                is PathSegment.Index -> startsElement(scan.content) && elementCount++ == segment.index
            }
            if (!matched) {
                if (scan.lowestClosing < targetDepth) return foundLine // le parent s'est refermé sans porter le segment : au mieux, sa ligne
                continue
            }
            foundLine = lineIndex + 1
            segmentIndex++
            if (segmentIndex == segments.size) return foundLine
            if (scan.depthAfter <= depthBefore) return foundLine // la valeur tient sur la ligne : au mieux, cette ligne
            targetDepth = depthBefore + 1
            elementCount = 0
        }
        return foundLine
    }

    private fun declaresKey(content: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("""^(?:"$escaped"|'$escaped'|$escaped)\s*:""").containsMatchIn(content)
    }

    private fun startsElement(content: String): Boolean = content.first() !in "]},"

    /** Le compte de profondeur d'une ligne, aveugle aux chaînes et aux commentaires ; un commentaire bloc survit d'une ligne à l'autre. */
    private class LineScanner {
        private var inBlockComment = false

        fun scan(line: String, depthBefore: Int): LineScan {
            var depth = depthBefore
            var lowestClosing = Int.MAX_VALUE
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
                            '}', ']' -> {
                                depth--
                                lowestClosing = minOf(lowestClosing, depth)
                            }
                        }
                    }
                }
                i++
            }
            return LineScan(depth, lowestClosing, if (firstSignificant < 0) "" else line.substring(firstSignificant).trimEnd())
        }
    }

    /**
     * Ce que le parcours retient d'une ligne : la profondeur après elle ; la plus basse où une fermeture l'a menée en cours de ligne,
     * [Int.MAX_VALUE] sans fermeture, parce que `}, {` referme un objet sans que la profondeur finale bouge ; et son contenu significatif
     * (dès le premier caractère hors espace et hors commentaire, vide sinon).
     */
    private class LineScan(val depthAfter: Int, val lowestClosing: Int, val content: String)
}
