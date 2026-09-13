package fr.moulou.storify

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutateIn
import fr.moulou.storify.core.set
import fr.moulou.storify.core.transaction
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationException
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/** Des défauts invalides dès la naissance : le fixture du chantier C-06. */
@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreValidator(InvalidDefaultsValidator::class)
data class InvalidDefaultsData(
    var name: String = "",
)

class InvalidDefaultsValidator : Validator<InvalidDefaultsData> {
    override fun validate(data: InvalidDefaultsData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
    }
}

/** L'opt-in du chantier C-05 : la validation à chaque update, non recommandée mais disponible. */
@Serializable
@StoreConfiguration(withAutoSave = false, validateOnUpdate = true)
@StoreValidator(GuardedDataValidator::class)
data class GuardedData(
    var name: String = "valide",
)

class GuardedDataValidator : Validator<GuardedData> {
    override fun validate(data: GuardedData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
    }
}

/** La voie annotée avec policy par défaut choisie par annotation : le champ ajouté au chantier C-03. */
@Serializable
@StoreConfiguration(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
data class AnnotatedPolicyData(
    var label: String = "x",
)

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

        // stringValue est sans annotation : la policy par défaut SKIP applique la valeur mais n'émet rien.
        // Depuis C-03, SKIP marque quand même le store dirty : la persistance ne dépend plus de la policy.
        assertFalse(store.isDirty)
        store.set(MyOwnData2::stringValue, "bloublou")
        assertEquals("bloublou", store.data.stringValue)
        assertTrue(updates.isEmpty())
        assertTrue(store.isDirty)

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

    @Test
    fun `un store TOML naît dans un dossier encore inexistant`() {
        val path = newStorePath("marker.json").parent.resolve("toml").resolve("sub").resolve("config.toml")
        val store = StoreFactory.create<MyOwnData>(path.toString(), format = TomlFormat(), config = snapshotNoAutoSave)

        assertEquals(MyOwnData.getDefault(), store.data)
        assertTrue(Files.exists(path)) // le fichier initial est né, dossiers compris : TomlFormat crée les parents depuis C-04
    }

    @Test
    fun `defaultUpdatePolicy se choisit par annotation`() {
        val path = newStorePath("annotated-policy.json")
        val store = StoreFactory.createFromConstructor<AnnotatedPolicyData>(path.toString())

        assertEquals(UpdatePolicy.SNAPSHOT, store.getUpdatePolicy(AnnotatedPolicyData::label))

        val updates = mutableListOf<Operation<AnnotatedPolicyData>>()
        store.registerOnUpdate { updates.add(it) }
        store.set(AnnotatedPolicyData::label, "y")
        assertEquals(1, updates.size) // la policy venue de l'annotation allume les callbacks, sans config explicite
    }

    @Test
    fun `l'auto-save réel persiste un update SKIP, puis close libère tout`() {
        val path = newStorePath("autosave.json")
        val store = StoreFactory.create<MyOwnData>(path.toString(), config = StoreConfig(withAutoSave = true, autoSaveIntervalMs = 100))

        val saves = mutableListOf<Operation<MyOwnData>>()
        store.registerOnSave { saves.add(it) }

        // Policy par défaut SKIP : l'update est muet pour les callbacks, mais marque dirty (C-03)...
        store.set(MyOwnData::stringValue, "autosaved")

        // ... et le tick le persiste quand même : on attend le save AUTO_SAVE (5 s de marge pour un tick de 100 ms).
        val deadline = System.currentTimeMillis() + 5_000
        while (saves.none { it is SaveOperation<*> && it.trigger == SaveTrigger.AUTO_SAVE } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(saves.any { it is SaveOperation<*> && it.trigger == SaveTrigger.AUTO_SAVE })
        assertTrue(path.readText().contains("autosaved"))

        store.close() // le scheduler meurt : sans close(), ce test retiendrait la JVM pour toujours (C-01)
        assertTrue(store.isClosed)
    }

    @Test
    fun `un store fermé refuse les écritures et close est idempotent`() {
        val path = newStorePath("closed.json")
        val store = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)

        store.set(MyOwnData::stringValue, "avant fermeture")
        store.close()
        store.close() // idempotent : silencieux

        assertTrue(store.isClosed)
        assertEquals("avant fermeture", store.data.stringValue) // la lecture reste permise
        assertTrue(path.readText().contains("avant fermeture")) // la sauvegarde d'adieu (CLOSE) a écrit le dirty

        assertThrows(IllegalStateException::class.java) { store.set(MyOwnData::stringValue, "trop tard") }
        assertThrows(IllegalStateException::class.java) { store.saveImmediate() }
        assertThrows(IllegalStateException::class.java) { store.reloadFromFile() }
    }

    @Test
    fun `aucun fichier temporaire ne survit à une sauvegarde`() {
        val path = newStorePath("clean.json")
        val store = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)

        store.set(MyOwnData::stringValue, "propre")
        store.saveImmediate()

        val leftovers = Files.list(path.parent).use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `un temporaire orphelin d'un crash passé est balayé à l'ouverture`() {
        val path = newStorePath("swept.json")
        val orphan = path.resolveSibling("${path.fileName}.deadbeef.tmp")
        orphan.writeText("{ tronqué par un faux crash")

        StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)

        assertFalse(Files.exists(orphan))
    }

    @Test
    fun `des sauvegardes concurrentes laissent toujours un fichier entier`() {
        val path = newStorePath("storm.json")
        val store = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)

        // La course du C-01 : plusieurs threads encodent vers le même fichier. Avant C-02, les flux
        // pouvaient s'entrelacer ; depuis, chaque save est un temporaire unique puis un rename atomique.
        val threads = (1..4).map { threadNumber ->
            Thread {
                repeat(25) { i ->
                    store.set(MyOwnData::stringValue, "t$threadNumber-i$i")
                    store.saveImmediate()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        store.close()

        // La preuve d'intégrité : un store neuf relit le fichier sans broncher, quelle que soit la valeur gagnante.
        val reloaded = StoreFactory.create<MyOwnData>(path.toString(), config = snapshotNoAutoSave)
        assertTrue(reloaded.data.stringValue.startsWith("t"))
    }

    @Test
    fun `des défauts invalides ne créent jamais de fichier`() {
        val path = newStorePath("never-born.json")

        val exception = assertThrows(ValidationException::class.java) { StoreFactory.createFromConstructor<InvalidDefaultsData>(path.toString()) }

        assertTrue(exception.message!!.contains("default data")) // le remède est dans le code, le message le dit
        assertFalse(Files.exists(path)) // C-06 : la validation passe avant toute écriture
    }

    @Test
    fun `une ressource invalide reste sur disque, erreurs enrichies des lignes`() {
        val path = newStorePath("bad-resource.json")

        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromResource<MyOwnData2>(path.toString(), "myowndata2_invalid.json")
        }

        assertTrue(Files.exists(path)) // la copie de la ressource reste, éditable : le voeu du TODO d'origine
        assertTrue(exception.message!!.contains("line")) // et les erreurs pointent la ligne dans cette copie
    }

    @Test
    fun `validateNow dénonce ce qu'un update silencieux a laissé entrer`() {
        val path = newStorePath("validate-now.json")
        val store = StoreFactory.create<MyOwnData2>(path.toString())

        assertTrue(store.validateNow().isValid)

        store.set(MyOwnData2::stringValue, "") // policy par défaut SKIP : la valeur entre sans un mot
        assertTrue(store.validateNow().isInvalid) // ... et validateNow la dénonce à la demande (C-05)
    }

    @Test
    fun `reloadFromFile revalide par défaut et laisse la mémoire intacte en échec`() {
        val path = newStorePath("reload-guard.json")
        val store = StoreFactory.create<MyOwnData2>(path.toString())
        store.set(MyOwnData2::stringValue, "sain")
        store.saveImmediate()

        path.writeText(path.readText().replace("\"sain\"", "\"\"")) // le fichier devient invalide, mais bien formé

        assertThrows(ValidationException::class.java) { store.reloadFromFile() }
        assertEquals("sain", store.data.stringValue) // la mémoire n'a pas bougé

        store.reloadFromFile(validate = false) // l'échappatoire documentée
        assertEquals("", store.data.stringValue)
    }

    @Test
    fun `validateOnUpdate refuse la valeur, restaure, et signale par une opération`() {
        val path = newStorePath("guarded.json")
        val store = StoreFactory.createFromConstructor<GuardedData>(path.toString())

        val operations = mutableListOf<Operation<GuardedData>>()
        store.registerOnUpdate { operations.add(it) }

        store.set(GuardedData::name, "") // refusé : pas d'exception, un rollback et une opération d'échec
        assertEquals("valide", store.data.name)
        val failure = assertInstanceOf(ValidationFailedOperation::class.java, operations.single())
        assertTrue(failure.validationError.contains("name"))

        store.transaction { name = "" } // la transaction invalide restaure tout
        assertEquals("valide", store.data.name)
        assertTrue(operations.any { it is TransactionOperation<*> && !it.success })
    }

    @Test
    fun `validateOnUpdate exige useDeepCopy`() {
        val path = newStorePath("guarded-nodeep.json")
        assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromConstructor<GuardedData>(path.toString(), config = StoreConfig(withAutoSave = false, useDeepCopy = false, validateOnUpdate = true))
        }
    }
}
