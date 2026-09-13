package fr.moulou.storify

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutateIn
import fr.moulou.storify.core.set
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationException
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ─── Les data classes d'essai ─────────────────────────────────────────────────────────

/** La voie nue : aucune annotation, tout se donne à la factory. */
@Serializable
data class MyOwnData(
    var stringValue: String,
    var intValue: Int,
    var myOwnData3: MyOwnData3,
) {
    companion object : Defaultable<MyOwnData> {
        override fun getDefault(): MyOwnData = MyOwnData("default", 0, myOwnData3 = MyOwnData3(mutableMapOf(), MyOwnData4(mutableMapOf())))
    }
}

@Serializable
data class MyOwnData3(
    var map: MutableMap<String, String>,
    var myOwnData4: MyOwnData4,
)

@Serializable
data class MyOwnData4(
    var map: MutableMap<String, String>,
)

/** La voie annotée : config, format et validator viennent des annotations ; le path, lui, reste explicite dans les tests. */
@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreFileFormat(StoreFileFormatType.JSON)
@StoreValidator(MyOwnData2Validator::class)
data class MyOwnData2(
    var stringValue: String,

    @StoreUpdatePolicy(UpdatePolicy.SHALLOW)
    var intValue: Int,
    var myOwnData3: MyOwnData3,
) {
    companion object : Defaultable<MyOwnData2> {
        override fun getDefault(): MyOwnData2 = MyOwnData2("default", 0, myOwnData3 = MyOwnData3(mutableMapOf(), MyOwnData4(mutableMapOf())))
    }
}

class MyOwnData2Validator : Validator<MyOwnData2> {
    override fun validate(data: MyOwnData2, ctx: ValidationContext) {
        if (data.stringValue.isEmpty()) {
            ctx.addError("stringValue", "stringValue cannot be empty")
        }
    }
}

// ─── Les tests ────────────────────────────────────────────────────────────────────────

/**
 * Le banc d'essai unitaire des stores : création, callbacks, persistance, rechargement,
 * ressource embarquée et validation au chargement. Tout tourne sans auto-save (le tick en
 * conditions réelles est le travail de Storibench) et en policy SNAPSHOT explicite : le
 * défaut de la lib est SKIP, qui éteint les callbacks et le marquage dirty (constat n° 2 du banc).
 *
 * Les fichiers vivent dans build\tmp\storify-tests\<uuid> plutôt que dans un @TempDir JUnit :
 * faute de close() (constat n° 3), le hook d'arrêt JVM de chaque store réécrit son fichier après
 * le ménage de JUnit ; dans build, ces résurrections sont inoffensives et gradlew clean les emporte.
 */
class MyOwnTest {

    private val snapshotNoAutoSave = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    private fun newStorePath(fileName: String): Path {
        val directory = Paths.get("build", "tmp", "storify-tests", UUID.randomUUID().toString())
        Files.createDirectories(directory)
        return directory.resolve(fileName)
    }

    @Test
    fun `création depuis le companion, set, callbacks et persistance`() {
        val path = newStorePath("myowndata.json")
        val store = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)
        assertEquals(MyOwnData.getDefault(), store.data)

        val updates = mutableListOf<Operation<MyOwnData>>()
        val saves = mutableListOf<Operation<MyOwnData>>()
        store.registerOnUpdate { updates.add(it) }
        store.registerOnSave { saves.add(it) }

        store.set(MyOwnData::stringValue, "Hello !")
        assertEquals("Hello !", store.data.stringValue)
        val setOperation = assertInstanceOf(SetOperation::class.java, updates.single())
        assertEquals("default", setOperation.old.valueOrNull)
        assertEquals("Hello !", setOperation.new.valueOrNull)

        store.saveImmediate()
        store.set(MyOwnData::intValue, 7)
        store.saveImmediate()

        assertEquals(2, saves.size)
        val firstSave = assertInstanceOf(SaveOperation::class.java, saves[0])
        assertInstanceOf(CapturedValue.Initial::class.java, firstSave.old) // premier save : la donnée initiale
        assertEquals(SaveTrigger.IMMEDIATE, firstSave.trigger)
        val secondSave = assertInstanceOf(SaveOperation::class.java, saves[1])
        assertInstanceOf(CapturedValue.DeepCopy::class.java, secondSave.old) // ensuite : le snapshot du save précédent

        // La persistance nue : un second store sur le même fichier relit ce qui a été écrit.
        val reloaded = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)
        assertEquals("Hello !", reloaded.data.stringValue)
        assertEquals(7, reloaded.data.intValue)
    }

    @Test
    fun `store annoté, policy SHALLOW par annotation, callback ciblé, et le défaut SKIP qui se tait`() {
        val path = newStorePath("myowndata2.json")
        val store = StoreFactory.create<MyOwnData2>(path.toString())

        assertEquals(UpdatePolicy.SHALLOW, store.getUpdatePolicy(MyOwnData2::intValue))

        val updates = mutableListOf<Operation<MyOwnData2>>()
        val targeted = mutableListOf<Operation<MyOwnData2>>()
        store.registerOnUpdate { updates.add(it) }
        store.registerOnUpdateOn(MyOwnData2::intValue) { targeted.add(it) }

        // stringValue est sans annotation : la policy par défaut SKIP applique la valeur mais n'émet rien (constat n° 2).
        store.set(MyOwnData2::stringValue, "bloublou")
        assertEquals("bloublou", store.data.stringValue)
        assertTrue(updates.isEmpty())

        // intValue porte @StoreUpdatePolicy(SHALLOW) : la valeur s'applique ET les callbacks parlent, le ciblé compris.
        store.set(MyOwnData2::intValue, 5)
        assertEquals(1, updates.size)
        assertEquals(1, targeted.size)

        // La mutation imbriquée s'applique (SKIP là aussi, faute d'annotation : pas de callback).
        store.mutateIn(MyOwnData3::map, { myOwnData3 }) { map -> map["one"] = "1" }
        assertEquals("1", store.data.myOwnData3.map["one"])

        store.saveImmediate()
        assertTrue(path.readText().contains("bloublou"))
    }

    @Test
    fun `reloadFromFile relit le fichier édité à la main et notifie onReload`() {
        val path = newStorePath("myowndata.json")
        val store = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)

        val reloads = mutableListOf<Operation<MyOwnData>>()
        store.registerOnReload { reloads.add(it) }

        path.writeText(path.readText().replace("\"default\"", "\"edited\"")) // le fichier écrit à la création, retouché comme à la main
        store.reloadFromFile()

        assertEquals("edited", store.data.stringValue)
        assertInstanceOf(ReloadOperation::class.java, reloads.single())
    }

    @Test
    fun `createFromResource copie la ressource embarquée au premier lancement`() {
        val path = newStorePath("fromresource.json")
        val store = StoreFactory.createFromResource<MyOwnData2>(path.toString(), "myowndata2_default.json")

        assertEquals("fromResource", store.data.stringValue)
        assertEquals(42, store.data.intValue)
        assertTrue(Files.exists(path))
    }

    @Test
    fun `un fichier invalide au chargement lève une ValidationException`() {
        val path = newStorePath("invalid.json")
        path.writeText(
            """
            {
                "stringValue": "",
                "intValue": 1,
                "myOwnData3": { "map": {}, "myOwnData4": { "map": {} } }
            }
            """.trimIndent()
        )

        val exception = assertThrows(ValidationException::class.java) { StoreFactory.create<MyOwnData2>(path.toString()) }
        assertTrue(exception.message!!.contains("stringValue"))
    }
}
