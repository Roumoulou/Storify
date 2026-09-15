package fr.moulou.storify.formats

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.utils.StoreFormats
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Le registre des formats : l'enregistrement insensible à la casse, la résolution par le chemin,
 * et le refus net de l'inconnu (C-09 : plus de repli silencieux sur JSON).
 */
class FormatRegistryTest {

    @Test
    fun `registerFormat rend l'extension résolvable, insensible à la casse`() {
        val format = CustomFormat()
        StoreFormats.registerFormat("MiXeD", format)

        assertSame(format, StoreFormats.getFormat("mixed"))
        assertSame(format, StoreFormats.getFormat("MIXED"))
    }

    @Test
    fun `getFormatForPath résout par l'extension du Path`() {
        assertInstanceOf(JsonFormat::class.java, StoreFormats.getFormatForPath(Paths.get("a", "b", "c.JSON")))
    }

    @Test
    fun `un chemin sans extension ou à point final est refusé`() {
        val noExtension = assertThrows(IllegalArgumentException::class.java) { StoreFormats.getFormatForStringPath("build/tmp/noextension") }
        assertTrue(noExtension.message!!.contains("registered extension"))

        assertThrows(IllegalArgumentException::class.java) { StoreFormats.getFormatForStringPath("build/tmp/trailing.") }
    }

    @Test
    fun `une extension inconnue est refusée avec les extensions connues au message`() {
        val exception = assertThrows(IllegalArgumentException::class.java) { StoreFormats.getFormat("zzz") }
        assertTrue(exception.message!!.contains("zzz"))
        assertTrue(exception.message!!.contains("json")) // le message nomme ce qui existe
    }
}
