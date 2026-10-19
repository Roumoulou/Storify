// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5 ; la visibilité est gardée, ce type faisant partie de l'API de Storify. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

public data class Json5EncoderConfig(
    /** Indentation per nesting level; only spaces and tabs are allowed. An empty value produces compact output. */
    val indent: String = "",
    val quoteStrategy: Json5QuoteStrategy = Json5QuoteStrategy.Single,
    val unquotedKey: Boolean = true,
    val trailingComma: Boolean = false,
) {
    init {
        requireValidJson5Indent(indent)
    }
}
