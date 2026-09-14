package fr.moulou.storify.lifecycle

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le hook d'arrêt JVM (C-23), testé par son corps extrait `runShutdownHook()` : le filet
 * anti-crash sauve le dirty, mais un store propre ne réécrit jamais une édition disque.
 */
class ShutdownHookTest {

    @Test
    fun `le hook d'arrêt ne sauve que dirty`() {
        val path = newStorePath("shutdown.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false))

        // Store propre : une édition disque faite pendant la session survit au hook.
        store.set(PlainData::name, "sauvé")
        store.saveImmediate()
        path.writeText(path.readText().replace("sauvé", "édité à la main"))
        store.runShutdownHook()
        assertTrue(path.readText().contains("édité à la main")) // rien à sauver : le hook n'a pas réécrit

        // Store dirty : le hook reste le filet anti-crash et écrit.
        store.set(PlainData::name, "après crash")
        store.runShutdownHook()
        assertTrue(path.readText().contains("après crash"))

        store.close()
    }
}
