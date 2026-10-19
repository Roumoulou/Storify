// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5, et les déclarations de premier niveau passées de public à internal. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

internal enum class Json5DiagnosticCode {
    UnexpectedCharacter,
    UnexpectedToken,
    ExpectedValue,
    ExpectedProperty,
    ExpectedColon,
    ExpectedCommaOrEnd,
    InvalidNumber,
    InvalidIdentifier,
    InvalidEscape,
    UnclosedString,
    UnclosedBlockComment,
    UnclosedContainer,
    MismatchedClosingToken,
}

internal data class Json5Diagnostic(
    public val code: Json5DiagnosticCode,
    public val range: Json5SourceRange,
    public val message: String,
)

internal class Json5ParseException(
    public val diagnostic: Json5Diagnostic,
) : IllegalStateException("${diagnostic.message} at index ${diagnostic.range.start}") {
    public val offset: Int
        get() = diagnostic.range.start
}
