package fr.moulou.storify.validation

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import java.nio.file.Path

/**
 * Enrichit les [ValidationError] avec les numéros de ligne du fichier JSON source.
 *
 * Lorsqu'une erreur de validation est détectée sur des données chargées depuis un fichier JSON,
 * cet objet permet de localiser la ligne exacte du champ fautif dans le fichier,
 * facilitant le diagnostic pour l'utilisateur.
 *
 * ## Fonctionnement
 *
 * 1. Le chemin de validation (ex: `GuildData.base.location.y`) est découpé en segments
 * 2. Chaque segment est recherché séquentiellement dans le fichier JSON en suivant la profondeur d'imbrication
 * 3. Les index de tableaux (`members[2]`) sont également supportés
 *
 * ## Limitations
 *
 * - Ne fonctionne qu'avec le format [JsonFormat] (retourne les erreurs inchangées pour les autres formats)
 * - Suppose un JSON formaté (pretty-printed), une clé par ligne
 * - Les erreurs de lecture du fichier sont silencieusement ignorées
 */
internal object ValidationErrorEnricher {

    /**
     * Enrichit chaque [ValidationError] de la liste avec le numéro de ligne JSON correspondant.
     *
     * @param format Le format du store — seul [JsonFormat] est supporté, les autres formats
     *               retournent la liste d'erreurs inchangée.
     * @param path   Chemin vers le fichier JSON source.
     * @param errors Liste d'erreurs de validation à enrichir.
     * @return La liste d'erreurs avec le champ [ValidationError.jsonLine] renseigné lorsque possible.
     */
    fun enrich(format: StoreFormat<*>, path: Path, errors: List<ValidationError>): List<ValidationError> {
        if (format !is JsonFormat) return errors
        return try {
            val fileLines = path.toFile().readLines()
            errors.map { error ->
                val line = findLineInFile(fileLines, error)
                if (line != null) error.copy(jsonLine = line) else error
            }
        } catch (_: Exception) {
            errors
        }
    }

    /**
     * Cherche la ligne du fichier JSON correspondant à une erreur de validation.
     *
     * Convertit le chemin de l'erreur en segments (clés JSON et index de tableau),
     * puis navigue dans les lignes du fichier pour trouver la correspondance.
     *
     * @param fileLines Lignes du fichier JSON source.
     * @param error     Erreur de validation contenant le chemin (`path`) et le champ (`field`).
     * @return Le numéro de ligne (1-indexed) ou `null` si non trouvé.
     */
    private fun findLineInFile(fileLines: List<String>, error: ValidationError): Int? {
        val segments = buildPathSegments(error) ?: return null
        return navigateJsonLines(fileLines, segments)
    }

    /**
     * Convertit le chemin d'une [ValidationError] en segments navigables.
     *
     * Le chemin `"GuildData.base.location.y"` avec `field = null` produit `["base", "location", "y"]`.
     * Le chemin `"GuildData.members[2]"` avec `field = "username"` produit `["members", 2, "username"]`.
     *
     * Les segments sont soit des [String] (clés JSON) soit des [Int] (index de tableau).
     *
     * @param error Erreur de validation dont on extrait le chemin.
     * @return Liste de segments, ou `null` si le chemin est vide.
     */
    private fun buildPathSegments(error: ValidationError): List<Any>? {
        val rawPath = buildString {
            val afterClass = error.path.substringAfter(".", "")
            if (afterClass.isNotEmpty()) append(afterClass)
            if (error.field != null) {
                if (isNotEmpty()) append(".")
                append(error.field)
            }
        }
        if (rawPath.isEmpty()) return null

        val segments = mutableListOf<Any>()
        for (part in rawPath.split(".")) {
            val bracketIdx = part.indexOf('[')
            if (bracketIdx < 0) {
                segments.add(part)
            } else {
                if (bracketIdx > 0) segments.add(part.substring(0, bracketIdx))
                segments.add(part.substring(bracketIdx + 1, part.indexOf(']')).toInt())
            }
        }
        return segments
    }

    /**
     * Parcourt les lignes d'un fichier JSON pour trouver la ligne correspondant à une séquence de segments.
     *
     * L'algorithme suit la profondeur d'imbrication JSON (`{`, `}`, `[`, `]`) et cherche
     * chaque segment au bon niveau de profondeur :
     * - **Segment [String]** : cherche une clé JSON `"segment"` au niveau de profondeur attendu.
     * - **Segment [Int]** : compte les objets `{` dans un tableau au bon niveau pour trouver l'index cible.
     *
     * Les caractères à l'intérieur des chaînes JSON sont ignorés pour le suivi de la profondeur.
     *
     * @param fileLines Lignes du fichier JSON (pretty-printed).
     * @param segments  Segments à naviguer (clés [String] et index [Int]).
     * @return Le numéro de ligne (1-indexed) du dernier segment trouvé, ou `null`.
     */
    private fun navigateJsonLines(fileLines: List<String>, segments: List<Any>): Int? {
        var segIdx = 0
        var depth = 0
        var targetDepth = 1
        var arrayCount = 0
        var seekingIndex = false
        var targetIndex = 0

        for ((lineIdx, rawLine) in fileLines.withIndex()) {
            if (segIdx >= segments.size) break
            val trimmed = rawLine.trim()
            val seg = segments[segIdx]

            when {
                seg is String && depth == targetDepth && trimmed.startsWith("\"$seg\"") -> {
                    segIdx++
                    if (segIdx >= segments.size) return lineIdx + 1
                    targetDepth = depth + 1
                }

                seg is Int && !seekingIndex -> {
                    seekingIndex = true
                    targetIndex = seg
                    arrayCount = 0
                }
            }

            if (seekingIndex && depth == targetDepth && trimmed.startsWith("{")) {
                if (arrayCount == targetIndex) {
                    segIdx++
                    seekingIndex = false
                    if (segIdx >= segments.size) return lineIdx + 1
                    targetDepth = depth + 1
                }
                arrayCount++
            }

            var inStr = false
            var esc = false
            for (ch in rawLine) {
                if (esc) { esc = false; continue }
                if (ch == '\\' && inStr) { esc = true; continue }
                if (ch == '"') { inStr = !inStr; continue }
                if (!inStr) when (ch) {
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
        }
        return null
    }
}
