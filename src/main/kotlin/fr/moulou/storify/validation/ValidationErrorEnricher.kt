// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import fr.moulou.storify.StoreFormat
import fr.moulou.storify.utils.withoutUtf8Bom
import java.nio.file.Path

/**
 * Enrichit les [ValidationError] du numéro de ligne où le fichier porte leur chemin (C-32) : le localisateur du format
 * ([StoreFormat.lineLocator], la famille JSON aujourd'hui) parcourt les lignes du fichier pour chaque erreur ; une erreur dont le chemin ne se
 * retrouve pas, ou ne se lit pas, reste sans ligne et les autres gardent la leur. Un format sans localisateur, ou un fichier illisible,
 * rendent les erreurs inchangées.
 */
object ValidationErrorEnricher {

    fun enrich(format: StoreFormat, path: Path, errors: List<ValidationError>): List<ValidationError> {
        val locator = format.lineLocator() ?: return errors
        val lines = runCatching { path.toFile().readLines().mapIndexed { index, line -> if (index == 0) line.withoutUtf8Bom() else line } }.getOrNull() ?: return errors
        return errors.map { error ->
            val line = runCatching { locator.lineOf(lines, ErrorPath.segments(error)) }.getOrNull()
            if (line != null) error.copy(line = line) else error
        }
    }

    /** Le résultat de [validator] sur [data], les erreurs enrichies des lignes de [path] : le geste partagé du chargement, du rechargement et de `validateFile`. */
    fun <T : Any> validate(format: StoreFormat, path: Path, data: T, validator: Validator<T>): ValidationResult {
        val result = validator.evaluate(data)
        return if (result is ValidationResult.Failure) ValidationResult.Failure(enrich(format, path, result.errors)) else result
    }
}
