// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.utils

import java.io.InputStream
import java.io.PushbackInputStream

/** Les trois octets du BOM UTF-8, le caractère U+FEFF que le Bloc-notes pose en tête d'un fichier « UTF-8 avec BOM ». */
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Le flux sans son BOM UTF-8 éventuel (C-31) : les trois premiers octets sont avalés s'ils valent `EF BB BF`, remis en place sinon, et le
 * reste du flux se lit tel quel. Storify lit avec ou sans BOM et écrit toujours sans : le BOM n'est pas du contenu.
 */
internal fun InputStream.withoutUtf8Bom(): InputStream {
    val stream = PushbackInputStream(this, UTF8_BOM.size)
    val head = ByteArray(UTF8_BOM.size)
    // Une boucle sur read plutôt que readNBytes : elle ne dépend d'aucune API au-dessus de Java 8 et vaut telle quelle pour les deux variantes (C-65, C-68).
    var read = 0
    while (read < head.size) {
        val n = stream.read(head, read, head.size - read)
        if (n < 0) break
        read += n
    }
    if (read == head.size && head.contentEquals(UTF8_BOM)) return stream
    if (read > 0) stream.unread(head, 0, read)
    return stream
}

/** Le texte sans son BOM UTF-8 éventuel (C-31). */
internal fun String.withoutUtf8Bom(): String = removePrefix("\uFEFF")
