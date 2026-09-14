package fr.moulou.storify.validation

import fr.moulou.storify.support.InnerLeaf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ValidationContext` en unitaire pur : l'accumulation, les chemins composés de l'imbrication
 * (`parent.champ`) et des collections (`champ[index]`), et les deux formats de sortie.
 */
class ValidationContextTest {

    /** Un validator qui échoue toujours, pour tracer les chemins composés. */
    private val alwaysFailing = object : Validator<InnerLeaf> {
        override fun validate(data: InnerLeaf, ctx: ValidationContext) {
            ctx.addError("label", "boom")
        }
    }

    @Test
    fun `check n'ajoute une erreur que si la condition est fausse, avec la valeur rejetée`() {
        val ctx = ValidationContext(currentPath = "Root", currentClassName = "Root")

        ctx.check(true, "ok", "jamais vu")
        assertTrue(ctx.errors.isEmpty())

        ctx.check(false, "field", "bad", 42)
        assertEquals(1, ctx.errorCount)
        assertEquals("field", ctx.errors.single().field)
        assertEquals(42, ctx.errors.single().rejectedValue)
        assertEquals("Root", ctx.errors.single().path)
    }

    @Test
    fun `addError porte un champ, addObjectError n'en porte pas`() {
        val ctx = ValidationContext(currentPath = "Root", currentClassName = "Root")

        ctx.addError("x", "message de champ")
        ctx.addObjectError("message d'objet")

        assertEquals("x", ctx.errors[0].field)
        assertNull(ctx.errors[1].field)
    }

    @Test
    fun `validateNested préfixe le chemin parent point champ`() {
        val ctx = ValidationContext(currentPath = "Root", currentClassName = "Root")

        ctx.validateNested("child", InnerLeaf(), alwaysFailing)

        val error = ctx.errors.single()
        assertEquals("Root.child", error.path)
        assertEquals("InnerLeaf", error.className)
    }

    @Test
    fun `validateEach indexe le chemin, listes et tableaux compris`() {
        val ctx = ValidationContext(currentPath = "Root", currentClassName = "Root")

        ctx.validateEach("items", listOf(InnerLeaf(), InnerLeaf()), alwaysFailing)
        ctx.validateEach("array", arrayOf(InnerLeaf()), alwaysFailing)

        assertEquals("Root.items[0]", ctx.errors[0].path)
        assertEquals("Root.items[1]", ctx.errors[1].path)
        assertEquals("Root.array[0]", ctx.errors[2].path)
    }

    @Test
    fun `formatErrors compte et détaille, formatErrorsShort condense`() {
        val empty = ValidationContext(currentPath = "Root", currentClassName = "Root")
        assertTrue(empty.formatErrors().startsWith("Validation passed"))
        assertEquals("OK", empty.formatErrorsShort())

        val ctx = ValidationContext(currentPath = "Root", currentClassName = "Root")
        ctx.addError("a", "un")
        ctx.addError("b", "deux")
        assertTrue(ctx.formatErrors().contains("2 error(s)"))
        assertTrue(ctx.formatErrorsShort().contains("Root.a: un"))
    }
}
