// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import fr.moulou.storify.StorifyException

/** Un fichier bien formé mais invalide, au chargement ou au rechargement d'un store : les [errors] du validator, et une [StorifyException] (C-33). */
class ValidationException(
    val errors: List<ValidationError>,
    message: String = buildMessage(errors)
) : StorifyException(message) {

    companion object {
        private fun buildMessage(errors: List<ValidationError>): String {
            return buildString {
                appendLine("Validation failed with ${errors.size} error(s):")
                errors.forEachIndexed { index, error ->
                    appendLine("  ${index + 1}. ${error.formatFull()}")
                }
            }.trimEnd()
        }
    }

    val errorCount: Int get() = errors.size
}
