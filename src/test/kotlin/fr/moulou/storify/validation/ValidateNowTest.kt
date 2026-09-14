package fr.moulou.storify.validation

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `validateNow` (C-05) : la validation à la demande sur les données en mémoire.
 */
class ValidateNowTest {

    @Test
    fun `validateNow dénonce ce qu'un update silencieux a laissé entrer`() {
        StoreFactory.createFromConstructor<AnnotatedValidatedData>(newStorePath("now.json").toString()).use { store ->
            assertTrue(store.validateNow().isValid)

            store.set(AnnotatedValidatedData::name, "") // policy par défaut SKIP : la valeur entre sans un mot

            assertTrue(store.validateNow().isInvalid) // ... et validateNow la dénonce à la demande
        }
    }

    @Test
    fun `sans validator, validateNow rend toujours Success`() {
        StoreFactory.create<PlainData>(newStorePath("novalidator.json").toString(), config = StoreConfig(withAutoSave = false)).use { store ->
            store.set(PlainData::name, "")
            assertTrue(store.validateNow().isValid)
        }
    }
}
