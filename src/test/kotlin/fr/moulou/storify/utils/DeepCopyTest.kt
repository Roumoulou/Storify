package fr.moulou.storify.utils

import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.TomlishData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Le deep copy CBOR : une copie réellement indépendante de n'importe quelle data class
 * `@Serializable`, sans interface de clonage ; et le piège des champs `Array`, épinglé.
 */
class DeepCopyTest {

    @Test
    fun `la copie est indépendante, muter la copie ne touche jamais l'original`() {
        val original = TomlishData()
        val copy = original.deepCopyViaCbor()

        copy.tags.add("copie")
        copy.limits["b"] = 2
        copy.leaf.hits.add(1)

        assertEquals(listOf("x", "y"), original.tags)
        assertEquals(mapOf("a" to 1), original.limits)
        assertTrue(original.leaf.hits.isEmpty())
    }

    @Test
    fun `deepCopyValue copie une valeur isolée, imbrications comprises`() {
        val value = mutableListOf(mutableMapOf("a" to 1))
        val copy = deepCopyValue(value)

        copy[0]["b"] = 2

        assertEquals(1, value[0].size)
        assertEquals(2, copy[0].size)
    }

    @Test
    fun `la torture passe, et le piège des champs Array dans equals est épinglé`() {
        val original = RandomData.default()
        val copy = original.deepCopyViaCbor()

        // Les contenus sont identiques...
        assertEquals(original.primitivesBlockVar, copy.primitivesBlockVar)
        assertEquals(original.dataBlockVar, copy.dataBlockVar)
        assertEquals(original.complexObject, copy.complexObject)
        assertTrue(original.intArrayVar.contentEquals(copy.intArrayVar))

        // ... mais l'equals de la data class dit non : ses champs Array se comparent par identité.
        assertNotEquals(original, copy)
    }
}
