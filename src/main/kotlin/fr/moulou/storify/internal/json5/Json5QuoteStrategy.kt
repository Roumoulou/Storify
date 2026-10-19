// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5 ; la visibilité est gardée, ce type faisant partie de l'API de Storify. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

/**
 * Selects the quote delimiter for a decoded string value. The `Prefer` strategies
 * switch delimiters when that avoids an escape; when both or neither delimiter
 * occurs, they use their named delimiter.
 */
public fun interface Json5QuoteStrategy {
    /** Returns `true` for a single quote or `false` for a double quote. */
    public fun quote(value: String): Boolean

    public data object Single : Json5QuoteStrategy {
        override fun quote(value: String): Boolean = true
    }

    public data object Double : Json5QuoteStrategy {
        override fun quote(value: String): Boolean = false
    }

    public data object PreferSingle : Json5QuoteStrategy {
        override fun quote(value: String): Boolean {
            return value.contains('"') || !value.contains('\'')
        }
    }

    public data object PreferDouble : Json5QuoteStrategy {
        override fun quote(value: String): Boolean {
            return value.contains('"') && !value.contains('\'')
        }
    }
}
