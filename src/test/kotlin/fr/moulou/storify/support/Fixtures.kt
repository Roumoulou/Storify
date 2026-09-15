package fr.moulou.storify.support

import fr.moulou.storify.*
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable

/*
 * Le zoo des fixtures de la suite (C-14), nommées par intention : chaque cas annoté exige sa
 * propre classe, les annotations étant statiques. Les chemins annotés pointent tous DANS
 * build\tmp\storify-tests ; les tests qui les utilisent passent d'abord par [resetAnnotatedFile].
 * Les fixtures de torture (RandomData, PrimitivesBlock, DatesBlock...) vivent à côté, dans
 * TestingClasses.kt, héritées des samples et promues ici.
 */

// ─── La voie nue : aucune annotation, companion Defaultable, constructeur sans argument ─────────

@Serializable
data class PlainData(
    var name: String = "steve",
    var count: Int = 0,
    var tags: MutableList<String> = mutableListOf("a"),
) {
    companion object : Defaultable<PlainData> {
        override fun getDefault(): PlainData = PlainData(name = "default", count = 1)
    }
}

// ─── L'imbrication : setIn/mutateIn, et la policy annotée sur une classe imbriquée ──────────────

@Serializable
data class InnerLeaf(
    var label: String = "leaf",

    @StoreUpdatePolicy(UpdatePolicy.SNAPSHOT)
    var hits: MutableList<Int> = mutableListOf(),
)

@Serializable
data class OuterData(
    var title: String = "outer",
    var leaf: InnerLeaf = InnerLeaf(),
    var registry: MutableMap<String, String> = mutableMapOf(),
) {
    companion object : Defaultable<OuterData> {
        override fun getDefault(): OuterData = OuterData()
    }
}

// ─── Le kit annoté complet ──────────────────────────────────────────────────────────────────────

@Serializable
@StorePath("build/tmp/storify-tests/annotated/annotated-data.json")
@StoreFileFormat(StoreFileFormatType.JSON)
@StoreConfiguration(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
@StoreValidator(AnnotatedDataValidator::class)
data class AnnotatedData(
    var greeting: String = "bonjour",

    @StoreUpdatePolicy(UpdatePolicy.SHALLOW)
    var uses: Long = 0,
) {
    companion object : Defaultable<AnnotatedData> {
        override fun getDefault(): AnnotatedData = AnnotatedData()
    }
}

class AnnotatedDataValidator : Validator<AnnotatedData> {
    override fun validate(data: AnnotatedData, ctx: ValidationContext) {
        ctx.check(data.greeting.isNotBlank(), "greeting", "must not be blank", data.greeting)
    }
}

// ─── La préséance format et config ──────────────────────────────────────────────────────────────

/** Annotée TOML : prouve que l'annotation bat l'extension du chemin, et qu'un format explicite bat l'annotation. */
@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreFileFormat(StoreFileFormatType.TOML)
data class AnnotatedTomlData(var title: String = "toml", var level: Int = 2)

/** Annotée JSON5 : la résolution du troisième format fourni par `@StoreFileFormat` (C-21). */
@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreFileFormat(StoreFileFormatType.JSON5)
data class AnnotatedJson5Data(var motto: String = "json5", var rank: Int = 1)

/** Annotée withMeta : prouve qu'une config explicite bat l'annotation (le sidecar comme témoin). */
@Serializable
@StoreConfiguration(withAutoSave = false, withMeta = true)
data class AnnotatedMetaData(var value: Int = 5)

// ─── L'accord withValidation (C-24) : true des deux côtés, le validator est l'opt-in ───────────

@Serializable
@StoreValidator(BareValidatedDataValidator::class)
data class BareValidatedData(var name: String = "ok")

class BareValidatedDataValidator : Validator<BareValidatedData> {
    override fun validate(data: BareValidatedData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
    }
}

@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreValidator(AnnotatedValidatedDataValidator::class)
data class AnnotatedValidatedData(var name: String = "ok")

class AnnotatedValidatedDataValidator : Validator<AnnotatedValidatedData> {
    override fun validate(data: AnnotatedValidatedData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
    }
}

// ─── La préséance des validators : l'annoté refuse tout, l'explicite accepte tout ───────────────

@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreValidator(RejectingValidator::class)
data class RejectingByAnnotationData(var name: String = "ok")

class RejectingValidator : Validator<RejectingByAnnotationData> {
    override fun validate(data: RejectingByAnnotationData, ctx: ValidationContext) {
        ctx.addObjectError("rejected by the annotated validator")
    }
}

class AcceptingValidator : Validator<RejectingByAnnotationData> {
    override fun validate(data: RejectingByAnnotationData, ctx: ValidationContext) {
        // accepte tout : si le store se construit, c'est lui qui a tourné, pas l'annoté
    }
}

// ─── Les échecs de la factory ───────────────────────────────────────────────────────────────────

@Serializable
data class NoCompanionData(var x: Int = 0)

@Serializable
data class NotDefaultableCompanionData(var x: Int = 0) {
    companion object {
        @Suppress("unused")
        const val TAG = "compagnon sans Defaultable"
    }
}

@Serializable
data class NoZeroArgConstructorData(var required: String)

/** PAS @Serializable : le fail-fast de C-09 doit refuser dès la création, avant tout fichier. */
class NotSerializableData(@Suppress("unused") var x: Int = 0)

/** L'opt-in du garde C-05 : la validation à chaque update, non recommandée mais disponible. */
@Serializable
@StoreConfiguration(withAutoSave = false, validateOnUpdate = true)
@StoreValidator(GuardedFixtureValidator::class)
data class GuardedFixture(var name: String = "valide")

class GuardedFixtureValidator : Validator<GuardedFixture> {
    override fun validate(data: GuardedFixture, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
    }
}

/** Des défauts invalides dès la naissance : le fixture du contrat C-06 (jamais de fichier écrit). */
@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreValidator(InvalidByDefaultValidator::class)
data class InvalidByDefaultData(var name: String = "")

class InvalidByDefaultValidator : Validator<InvalidByDefaultData> {
    override fun validate(data: InvalidByDefaultData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
    }
}

// ─── Les Defaultable externes ───────────────────────────────────────────────────────────────────

class ExternalPlainDefaults : Defaultable<PlainData> {
    override fun getDefault(): PlainData = PlainData(name = "external", count = 7)
}

class BrokenExternalDefaults(@Suppress("unused") val needed: String) : Defaultable<PlainData> {
    override fun getDefault(): PlainData = PlainData()
}

class ExternalAnnotatedDefaults : Defaultable<AnnotatedData> {
    override fun getDefault(): AnnotatedData = AnnotatedData(greeting = "externe", uses = 9)
}

// ─── Les ressources embarquées (fichiers dans src\test\resources) ──────────────────────────────

@Serializable
@StorePath("build/tmp/storify-tests/annotated/resource-data.json")
@StoreDefaultResource("resource-data_default.json")
@StoreConfiguration(withAutoSave = false, withValidation = false)
data class ResourceData(var origin: String = "code", var level: Int = 0)

@Serializable
@StoreConfiguration(withAutoSave = false)
@StoreValidator(ValidatedResourceDataValidator::class)
data class ValidatedResourceData(var origin: String = "code", var level: Int = 0)

class ValidatedResourceDataValidator : Validator<ValidatedResourceData> {
    override fun validate(data: ValidatedResourceData, ctx: ValidationContext) {
        ctx.check(data.level >= 0, "level", "must be non-negative", data.level)
    }
}

// ─── La fixture TOML modeste (types que le format TOML représente sans détour) ─────────────────

@Serializable
data class TomlishData(
    var title: String = "storify",
    var level: Int = 3,
    var ratio: Double = 0.5,
    var enabled: Boolean = true,
    var tags: MutableList<String> = mutableListOf("x", "y"),
    var limits: MutableMap<String, Int> = mutableMapOf("a" to 1),
    var leaf: InnerLeaf = InnerLeaf(),
) {
    companion object : Defaultable<TomlishData> {
        override fun getDefault(): TomlishData = TomlishData()
    }
}
