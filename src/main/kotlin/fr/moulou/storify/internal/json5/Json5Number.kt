// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5, et les déclarations de premier niveau passées de public à internal. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

/**
 * A parsed JSON5 number in normalized form. Use [Json5Document.text] with the
 * containing [Json5NumberLiteral] when the original source spelling is needed.
 */
internal class Json5Number internal constructor(
    public val value: String,
) : Number() {
    override fun toString(): String {
        return value
    }

    override fun toByte(): Byte {
        return value.toDouble().toInt().toByte()
    }

    override fun toDouble(): Double {
        return value.toDouble()
    }

    override fun toFloat(): Float {
        return value.toDouble().toFloat()
    }

    override fun toInt(): Int {
        return value.toDouble().toInt()
    }

    override fun toLong(): Long {
        return value.toDouble().toLong()
    }

    override fun toShort(): Short {
        return value.toDouble().toInt().toShort()
    }
}
