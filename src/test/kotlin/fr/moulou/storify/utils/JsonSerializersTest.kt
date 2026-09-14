package fr.moulou.storify.utils

import fr.moulou.storify.serializers.JsonPrimitiveAsStringSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `JsonPrimitiveAsStringSerializer` : la primitive JSON transportée telle quelle dans une chaîne,
 * et reparse à l'identique au retour.
 */
class JsonSerializersTest {

    private fun roundTrip(primitive: JsonPrimitive): JsonPrimitive {
        val encoded = Json.encodeToString(JsonPrimitiveAsStringSerializer, primitive)
        return Json.decodeFromString(JsonPrimitiveAsStringSerializer, encoded)
    }

    @Test
    fun `chaîne, nombre et booléen font l'aller-retour à l'identique`() {
        assertEquals(JsonPrimitive("txt"), roundTrip(JsonPrimitive("txt")))
        assertEquals(JsonPrimitive(5), roundTrip(JsonPrimitive(5)))
        assertEquals(JsonPrimitive(true), roundTrip(JsonPrimitive(true)))
    }
}
