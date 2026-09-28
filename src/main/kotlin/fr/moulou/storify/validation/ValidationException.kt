// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

class ValidationException(
    val errors: List<ValidationError>,
    message: String = buildMessage(errors)
) : IllegalStateException(message) {

    val errorCount: Int get() = errors.size

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
}
