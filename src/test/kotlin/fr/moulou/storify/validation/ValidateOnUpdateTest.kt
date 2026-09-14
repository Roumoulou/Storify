package fr.moulou.storify.validation

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.GuardedFixture
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * L'opt-in `validateOnUpdate` (C-05) : chaque geste est validé, l'échec restaure et se signale
 * par une opération plutôt que par une exception ; le garde exige le deep copy.
 */
class ValidateOnUpdateTest {

    @Test
    fun `un update invalide est refusé, restauré, et signalé par une opération`() {
        StoreFactory.createFromConstructor<GuardedFixture>(newStorePath("guarded.json").toString()).use { store ->
            val operations = mutableListOf<Operation<GuardedFixture>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(GuardedFixture::name, "") // refusé : pas d'exception, un rollback et une opération d'échec

            assertEquals("valide", store.data.name)
            val failure = assertInstanceOf(ValidationFailedOperation::class.java, operations.single())
            assertTrue(failure.validationError.contains("name"))
        }
    }

    @Test
    fun `une transaction invalide restaure tout et se signale success faux`() {
        StoreFactory.createFromConstructor<GuardedFixture>(newStorePath("guardedtx.json").toString()).use { store ->
            val operations = mutableListOf<Operation<GuardedFixture>>()
            store.registerOnUpdate { operations.add(it) }

            store.transaction { name = "" }

            assertEquals("valide", store.data.name)
            assertTrue(operations.any { it is TransactionOperation<*> && !it.success })
        }
    }

    @Test
    fun `validateOnUpdate exige useDeepCopy, le seul rollback générique`() {
        assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromConstructor<GuardedFixture>(
                newStorePath("guarded-nodeep.json").toString(),
                config = StoreConfig(withAutoSave = false, useDeepCopy = false, validateOnUpdate = true)
            )
        }
    }
}
