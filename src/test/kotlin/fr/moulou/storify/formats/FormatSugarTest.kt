package fr.moulou.storify.formats

import fr.moulou.storify.StoreFormat
import fr.moulou.storify.decodeFromPath
import fr.moulou.storify.encodeToPath
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Le sucre réifié de C-09 : `encodeToPath<T>` et `decodeFromPath<T>` matérialisent le sérialiseur
 * au site d'appel et passent par le contrat polymorphe ; le récepteur est typé `StoreFormat`,
 * l'interface, pour prouver que le dispatch est bien virtuel.
 */
class FormatSugarTest {

    @Test
    fun `le sucre réifié encode et décode à travers le contrat polymorphe`() {
        val format: StoreFormat = CustomFormat() // typé par l'interface, exprès
        val path = newStorePath("sugar.custom")
        val original = TomlishData(title = "sucre", level = 12)

        format.encodeToPath(original, path)
        val decoded: TomlishData = format.decodeFromPath(path)

        assertEquals(original, decoded)
    }
}
