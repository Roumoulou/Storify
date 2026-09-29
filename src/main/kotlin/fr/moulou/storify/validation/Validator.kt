// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

interface Validator<T : Any> {
    fun validate(data: T, ctx: ValidationContext)
}

/** Exécute le validator sur [data] dans un contexte racine nommé d'après sa classe, et rend le résultat (C-32). */
fun <T : Any> Validator<T>.evaluate(data: T): ValidationResult {
    val name = data::class.simpleName ?: "Unknown"
    val ctx = ValidationContext(currentPath = name, currentClassName = name)
    validate(data, ctx)
    return if (ctx.hasErrors) ValidationResult.Failure(ctx.errors) else ValidationResult.Success
}
