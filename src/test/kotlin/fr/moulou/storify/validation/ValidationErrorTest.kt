package fr.moulou.storify.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Le façonnage d'une `ValidationError` : le chemin complet dans ses variantes, la valeur rejetée
 * habillée selon son type, la ligne quand elle est connue.
 */
class ValidationErrorTest {

    @Test
    fun `formatFull assemble chemin, champ, message, valeur et ligne`() {
        val error = ValidationError(path = "Root.child", className = "C", field = "name", message = "bad", rejectedValue = "x", jsonLine = 7)
        val full = error.formatFull()

        assertTrue(full.contains("[Root.child.name]"))
        assertTrue(full.contains("name: bad"))
        assertTrue(full.contains("(was: \"x\")"))
        assertTrue(full.contains("line 7"))
    }

    @Test
    fun `sans path le champ porte seul le chemin, sans champ le path le porte`() {
        val fieldOnly = ValidationError(path = "", className = "C", field = "f", message = "m")
        assertTrue(fieldOnly.formatFull().startsWith("[f]"))

        val pathOnly = ValidationError(path = "Root", className = "C", field = null, message = "m")
        assertTrue(pathOnly.formatFull().startsWith("[Root]"))
        assertEquals("Root: m", pathOnly.formatShort())
    }

    @Test
    fun `formatValue habille la valeur selon son type`() {
        val string = ValidationError(path = "R", className = "C", field = "f", message = "m", rejectedValue = "abc")
        assertTrue(string.formatFull().contains("(was: \"abc\")"))

        val char = ValidationError(path = "R", className = "C", field = "f", message = "m", rejectedValue = 'z')
        assertTrue(char.formatFull().contains("(was: 'z')"))

        val collection = ValidationError(path = "R", className = "C", field = "f", message = "m", rejectedValue = listOf(1, 2))
        assertTrue(collection.formatFull().contains("Collection(size=2)"))

        val nothingRejected = ValidationError(path = "R", className = "C", field = "f", message = "m", rejectedValue = null)
        assertFalse(nothingRejected.formatFull().contains("was:")) // null = pas de valeur à montrer
    }
}
