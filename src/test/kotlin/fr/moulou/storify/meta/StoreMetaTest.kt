package fr.moulou.storify.meta

import fr.moulou.storify.StoreMeta
import fr.moulou.storify.support.awaitTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `StoreMeta` en unitaire pur : `touch()` et la map `custom`.
 */
class StoreMetaTest {

    private val timestampPattern = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3}""")

    @Test
    fun `touch met lastModified à jour, au motif exact`() {
        val meta = StoreMeta()
        val before = meta.lastModified

        awaitTrue(timeoutMs = 15) { false } // laisse l'horloge avancer
        meta.touch()

        assertTrue(meta.lastModified >= before)
        assertTrue(timestampPattern.matches(meta.lastModified))
    }

    @Test
    fun `setCustom et getCustom font la paire, l'absent rend null`() {
        val meta = StoreMeta()
        meta.setCustom("k", "v")

        assertEquals("v", meta.getCustom("k"))
        assertNull(meta.getCustom("absent"))
    }
}
