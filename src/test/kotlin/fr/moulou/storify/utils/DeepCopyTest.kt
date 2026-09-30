// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.utils

import fr.moulou.storify.CapturedValue
import fr.moulou.storify.MutateOperation
import fr.moulou.storify.Operation
import fr.moulou.storify.UpdatePolicy
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.ShapedRule
import fr.moulou.storify.support.ShapedRulesData
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le deep copy par arbre JSON (C-29) : une copie réellement indépendante de n'importe quelle data class `@Serializable`, sans interface de
 * clonage, sérialiseurs écrits pour le JSON compris ; le piège des champs `Array`, épinglé ; et le store qui copie par le copieur de son format.
 */
class DeepCopyTest {

    @Test
    fun `la copie est indépendante, muter la copie ne touche jamais l'original`() {
        val original = TomlishData()
        val copy = original.deepCopy()

        copy.tags.add("copie")
        copy.limits["b"] = 2
        copy.leaf.hits.add(1)

        assertEquals(listOf("x", "y"), original.tags)
        assertEquals(mapOf("a" to 1), original.limits)
        assertTrue(original.leaf.hits.isEmpty())
    }

    @Test
    fun `une valeur isolée se copie aussi, imbrications comprises`() {
        val value = mutableListOf(mutableMapOf("a" to 1))
        val copy = value.deepCopy()

        copy[0]["b"] = 2

        assertEquals(1, value[0].size)
        assertEquals(2, copy[0].size)
    }

    @Test
    fun `la torture passe, et le piège des champs Array dans equals est épinglé`() {
        val original = RandomData.default()
        val copy = original.deepCopy()

        // Les contenus sont identiques...
        assertEquals(original.primitivesBlockVar, copy.primitivesBlockVar)
        assertEquals(original.dataBlockVar, copy.dataBlockVar)
        assertEquals(original.complexObject, copy.complexObject)
        assertTrue(original.intArrayVar.contentEquals(copy.intArrayVar))

        // ... mais l'equals de la data class dit non : ses champs Array se comparent par identité.
        assertNotEquals(original, copy)
    }

    @Test
    fun `un sérialiseur écrit pour le JSON se copie par l'arbre JSON`() {
        val original = ShapedRulesData(mutableMapOf("fly" to ShapedRule("allow"), "tp" to ShapedRule("deny", "sauf les ops")))
        val copy = original.deepCopy()

        assertEquals(original, copy)
        assertNotSame(original.rules, copy.rules)
    }

    @Serializable
    data class Edge(var ratio: Double = Double.NaN, var big: Long = Long.MIN_VALUE, var byKey: Map<InnerLeaf, Int> = mapOf(InnerLeaf("k") to 1))

    @Test
    fun `NaN, un Long extrême et une clé de map structurée survivent à la copie`() {
        val copy = Edge().deepCopy()

        assertTrue(copy.ratio.isNaN())
        assertEquals(Long.MIN_VALUE, copy.big)
        assertEquals(1, copy.byKey[InnerLeaf("k")])
    }

    @Test
    fun `le store copie par le copieur de son format, sérialiseur écrit pour le JSON compris`() {
        val path = newStorePath("shaped.json")
        val config = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
        StoreFactory.create<ShapedRulesData>(path.toString(), config = config).use { store ->
            val updates = mutableListOf<Operation<ShapedRulesData>>()
            store.registerOnUpdate { updates.add(it) }

            store.mutate(ShapedRulesData::rules) { it["tp"] = ShapedRule("deny", "sauf les ops") }
            store.saveImmediate()

            val mutate = assertInstanceOf(MutateOperation::class.java, updates.single())
            assertInstanceOf(CapturedValue.DeepCopy::class.java, mutate.old) // la capture d'avant, copiée par l'arbre JSON du format
            assertTrue(path.readText().contains("\"sauf les ops\""))

            path.writeText(path.readText().replace("\"allow\"", "\"deny\"")) // la forme courte, éditée à la main
            store.reloadFromFile()
            assertEquals("deny", store.data.rules.getValue("fly").value)
        }
    }
}
