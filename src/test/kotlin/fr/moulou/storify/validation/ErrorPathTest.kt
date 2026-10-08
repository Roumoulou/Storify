// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** La grammaire des chemins d'erreur (C-32) et son rendu (C-55) : `render` est le miroir de `parse`, et un chemin rendu se relit en lui-même. */
class ErrorPathTest {

    private fun key(name: String) = PathSegment.Key(name)

    private fun index(index: Int) = PathSegment.Index(index)

    @Test
    fun `render joint les clés par un point, met un index entre crochets, et protège entre crochets et guillemets une clé vide ou qui porte un point ou un crochet`() {
        assertEquals("", ErrorPath.render(emptyList()))
        assertEquals("version", ErrorPath.render(listOf(key("version"))))
        assertEquals("groups.vip", ErrorPath.render(listOf(key("groups"), key("vip"))))
        assertEquals("members[1].priority", ErrorPath.render(listOf(key("members"), index(1), key("priority"))))
        assertEquals("list[2][0].a", ErrorPath.render(listOf(key("list"), index(2), index(0), key("a"))))
        assertEquals("[1].a", ErrorPath.render(listOf(index(1), key("a"))))
        assertEquals("groups.123", ErrorPath.render(listOf(key("groups"), key("123"))))
        assertEquals("homes[\"my.home\"].x", ErrorPath.render(listOf(key("homes"), key("my.home"), key("x"))))
        assertEquals("[\"a[0\"]", ErrorPath.render(listOf(key("a[0"))))
        assertEquals("[\"\"]", ErrorPath.render(listOf(key(""))))
    }

    @Test
    fun `un chemin rendu se relit en lui-même, une clé en chiffres restant une clé`() {
        for (raw in listOf("version", "groups.vip", "members[1].priority", "list[2][0].a", "[1].a", "homes[\"my.home\"].x", "a[b].c", "a['b'].c")) {
            val segments = ErrorPath.parse(raw)
            assertEquals(segments, ErrorPath.parse(ErrorPath.render(segments)), raw)
        }
        val digits = listOf(key("groups"), key("123"), key("weight"))
        assertEquals(digits, ErrorPath.parse(ErrorPath.render(digits)))
        assertEquals(listOf(key("a[0"), key("")), ErrorPath.parse(ErrorPath.render(listOf(key("a[0"), key(""))))) // un crochet fermant dans une clé ne se relit pas, limite écrite de parse
    }
}
