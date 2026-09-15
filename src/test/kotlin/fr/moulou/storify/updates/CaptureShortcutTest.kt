package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.GuardedFixture
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// ─── La fixture au compteur : chaque copie CBOR passe par serialize, et se compte ───────────────

@Serializable(with = CountedBoxSerializer::class)
class CountedBox(var value: Int = 0)

object CountedBoxSerializer : KSerializer<CountedBox> {
    var serializations = 0
    override val descriptor = PrimitiveSerialDescriptor("CountedBox", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: CountedBox) {
        serializations++
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): CountedBox = CountedBox(decoder.decodeInt())
}

@Serializable
data class CountedData(var box: CountedBox = CountedBox(), var name: String = "steve") {
    companion object : Defaultable<CountedData> {
        override fun getDefault(): CountedData = CountedData()
    }
}

/**
 * C-25 : le pipeline ne capture que devant public. Le compteur de sérialisations mesure les copies
 * CBOR réellement faites : sans auditeur, un update observant n'en fait aucune ; le garde
 * `validateOnUpdate`, lui, reste intact dans la branche rapide.
 */
class CaptureShortcutTest {

    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    @Test
    fun `sans auditeur, un set SNAPSHOT ne copie rien`() {
        StoreFactory.create<CountedData>(newStorePath("counted.json").toString(), config = snapshotConfig).use { store ->
            val before = CountedBoxSerializer.serializations

            store.set(CountedData::box, CountedBox(5))

            assertEquals(before, CountedBoxSerializer.serializations) // aucune capture : le compteur n'a pas bougé
            assertTrue(store.isDirty)                                 // la persistance, elle, est marquée comme toujours
            assertEquals(5, store.data.box.value)
        }
    }

    @Test
    fun `avec un auditeur, les captures reprennent, l'avant et l'après`() {
        StoreFactory.create<CountedData>(newStorePath("counted-heard.json").toString(), config = snapshotConfig).use { store ->
            store.registerOnUpdate { }
            val before = CountedBoxSerializer.serializations

            store.set(CountedData::box, CountedBox(7))

            assertEquals(before + 2, CountedBoxSerializer.serializations) // la copie d'avant et celle d'après
        }
    }

    @Test
    fun `la transaction ne copie l'après que devant public, le secours du rollback restant toujours pris`() {
        StoreFactory.create<CountedData>(newStorePath("counted-tx.json").toString(), config = snapshotConfig).use { store ->
            val silent = CountedBoxSerializer.serializations
            store.transaction { name = "sans public" }
            assertEquals(silent + 1, CountedBoxSerializer.serializations) // le secours seul : une copie de racine

            store.registerOnUpdate { }
            val heard = CountedBoxSerializer.serializations
            store.transaction { name = "devant public" }
            assertEquals(heard + 2, CountedBoxSerializer.serializations) // le secours, plus la copie d'après pour l'opération
        }
    }

    @Test
    fun `le raccourci n'affaiblit pas le garde validateOnUpdate`() {
        val guarded = StoreConfig(withAutoSave = false, validateOnUpdate = true, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
        StoreFactory.createFromConstructor<GuardedFixture>(newStorePath("guarded-shortcut.json").toString(), config = guarded).use { store ->
            store.set(GuardedFixture::name, "") // policy observante mais aucun auditeur : la branche rapide, garde compris

            assertEquals("valide", store.data.name) // refusé et restauré, comme devant public
        }
    }
}
