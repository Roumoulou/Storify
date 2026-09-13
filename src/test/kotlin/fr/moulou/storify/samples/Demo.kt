package fr.moulou.storify.samples

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * L'assemblage des deux stores d'exemple : la voie annotée ([SimpleHomesStoreWithAnnotations],
 * dont le path vient de `@StorePath` et l'auto-save est coupé par `@StoreConfiguration`) et la
 * voie nue ([SimpleHomesStoreWithoutAnnotations], tout explicite). Les fichiers sont supprimés
 * d'abord, pour que la démo reparte toujours d'un état connu.
 */
class Demo {

    @Test
    fun test() {
        val annotatedFile = File("C:\\temp\\SimpleHomesStoreWithAnnotations.json")
        val plainFile = File("build/tmp/storify-tests/SimpleHomesStoreWithoutAnnotations.json")
        annotatedFile.delete()
        plainFile.delete()

        // La voie annotée : path, config et validator viennent des annotations de la classe.
        val annotatedStore = StoreFactory.create<SimpleHomesStoreWithAnnotations>()
        assertTrue(annotatedFile.exists())
        assertTrue(annotatedStore.data.playersHomes.isNotEmpty())

        // La voie nue : tout se donne à la factory, la classe ne porte aucune annotation de store.
        val plainStore = StoreFactory.create<SimpleHomesStoreWithoutAnnotations>(plainFile.path, config = StoreConfig(withAutoSave = false))
        assertTrue(plainFile.exists())
        assertTrue(plainStore.data.playersHomes.isNotEmpty())
    }
}
