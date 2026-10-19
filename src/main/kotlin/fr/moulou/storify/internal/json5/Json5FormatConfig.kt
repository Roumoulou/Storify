// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5, et les déclarations de premier niveau passées de public à internal. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

internal enum class Json5LineSeparator {
    /** Uses the first separator found in the source, or LF when none is present. */
    Preserve,
    Lf,
    CrLf,
}

internal enum class Json5TrailingComma {
    Preserve,
    Always,
    Never,
}

internal enum class Json5FinalNewline {
    Preserve,
    Always,
    Never,
}

internal data class Json5FormatConfig(
    /** Indentation per nesting level; only spaces and tabs are allowed. */
    public val indent: String = "  ",
    public val lineSeparator: Json5LineSeparator = Json5LineSeparator.Preserve,
    public val trailingComma: Json5TrailingComma = Json5TrailingComma.Preserve,
    public val finalNewline: Json5FinalNewline = Json5FinalNewline.Preserve,
    /**
     * Re-encodes string literals and property names with this strategy.
     * `null` preserves their original source representation.
     */
    public val quoteStrategy: Json5QuoteStrategy? = null,
    /**
     * Whether property names eligible for identifier syntax are left unquoted
     * when [quoteStrategy] enables re-encoding.
     */
    public val unquotedKey: Boolean = true,
) {
    init {
        requireValidJson5Indent(indent)
    }
}
