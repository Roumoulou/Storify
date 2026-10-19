// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5, et les déclarations de premier niveau passées de public à internal. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

/**
 * Replaces [length] source positions starting at the zero-based [offset] with [content].
 * A zero [length] represents an insertion.
 */
internal data class Json5TextEdit(
    public val offset: Int,
    public val length: Int,
    public val content: String,
) {
    init {
        require(offset >= 0) { "offset must not be negative" }
        require(length >= 0) { "length must not be negative" }
    }
}

/**
 * Applies non-overlapping [edits] expressed against this original string.
 * The input list does not need to be sorted.
 *
 * @throws IllegalArgumentException if an edit is out of bounds or edits overlap.
 */
internal fun String.applyJson5Edits(
    edits: List<Json5TextEdit>,
): String {
    if (edits.isEmpty()) return this

    val ordered = edits.mapIndexed { index, edit -> index to edit }.sortedWith(
        compareBy<Pair<Int, Json5TextEdit>> { it.second.offset }
            .thenBy { it.second.length }
            .thenBy { it.first },
    )
    var previousEnd = 0
    for ((_, edit) in ordered) {
        require(edit.offset + edit.length <= length) { "edit ends outside of the source" }
        require(edit.offset >= previousEnd) { "edits must not overlap" }
        previousEnd = maxOf(previousEnd, edit.offset + edit.length)
    }

    val builder = StringBuilder(this)
    for ((_, edit) in ordered.asReversed()) {
        builder.setRange(edit.offset, edit.offset + edit.length, edit.content)
    }
    return builder.toString()
}

internal fun singleReplacementEdit(source: String, formatted: String): List<Json5TextEdit> {
    if (source == formatted) return emptyList()
    val limit = minOf(source.length, formatted.length)
    var prefix = 0
    while (prefix < limit && source[prefix] == formatted[prefix]) prefix++

    var suffix = 0
    while (
        suffix < source.length - prefix &&
        suffix < formatted.length - prefix &&
        source[source.lastIndex - suffix] == formatted[formatted.lastIndex - suffix]
    ) {
        suffix++
    }
    return listOf(
        Json5TextEdit(
            offset = prefix,
            length = source.length - prefix - suffix,
            content = formatted.substring(prefix, formatted.length - suffix),
        ),
    )
}
