package fr.moulou.storify.core

import fr.moulou.storify.*
import fr.moulou.storify.utils.DateUtils.formatLocal
import fr.moulou.storify.utils.deepCopyValue
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationErrorEnricher
import fr.moulou.storify.validation.ValidationResult
import fr.moulou.storify.validation.ValidationException
import fr.moulou.storify.validation.Validator
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.time.Clock

/**
 * Configuration d'une instance [BaseStore].
 *
 * @property withValidation    Active la validation au chargement initial (fichier ou données par défaut). Défaut `false`.
 * @property withAutoSave      Persiste automatiquement les données modifiées sur disque. Défaut `true`.
 * @property withMeta          Gère un fichier sidecar `.meta.json` (lastModified, etc.). Défaut `false`.
 * @property useDeepCopy       Deep-copy les données pour capturer old/new dans les callbacks et permettre
 *                             le rollback des transactions en cas d'exception. Défaut `true`.
 *                             Désactiver améliore les performances mais les snapshots old seront indisponibles.
 * @property defaultUpdatePolicy Politique d'update par défaut pour les propriétés sans annotation
 *                               [StoreUpdatePolicy]. Défaut [UpdatePolicy.SKIP] : sans policy explicite, les callbacks
 *                               se taisent. La persistance (marquage dirty), elle, est garantie pour toutes les policies.
 * @property autoSaveIntervalMs  Intervalle en millisecondes entre chaque tick d'auto-save. Défaut 5 min.
 */
data class StoreConfig(
    val withValidation: Boolean = false,
    val withAutoSave: Boolean = true,
    val withMeta: Boolean = false,
    val useDeepCopy: Boolean = true,
    val defaultUpdatePolicy: UpdatePolicy = UpdatePolicy.SKIP,
    val autoSaveIntervalMs: Long = 300_000L
)

/**
 * Implémentation principale de [Store].
 *
 * Gère le cycle de vie complet d'un fichier de données :
 * chargement (depuis fichier ou défaut), validation initiale, mutation synchrone,
 * auto-save, et persistance à l'arrêt.
 *
 * ## Thread safety
 *
 * Toutes les lectures/écritures de [_data] passent par [dataLock] (`ReentrantReadWriteLock`).
 *
 * @param DATA La data class serializable gérée par ce store.
 */
@Suppress("PropertyName")
class BaseStore<DATA : Any>(
    override val path: Path,
    override val format: StoreFormat<*>,

    /** Options de comportement du store. */
    @PublishedApi internal val config: StoreConfig,

    /** Désérialise [DATA] depuis le fichier au chemin donné. */
    private val dataDecoder: (Path) -> DATA,

    /** Sérialise [DATA] vers le fichier au chemin donné. */
    private val dataEncoder: (DATA, Path) -> Unit,

    /** Désérialise [StoreMeta] depuis le sidecar `.meta.json`. */
    metaDecoder: (Path) -> StoreMeta,

    /** Sérialise [StoreMeta] vers le sidecar `.meta.json`. */
    private val metaEncoder: (StoreMeta, Path) -> Unit,

    /** Factory fournissant la [DATA] par défaut quand aucun fichier n'existe. */
    private val defaultDataProvider: () -> DATA,

    /** Fonction de deep-copy (typiquement round-trip CBOR). Utilisée pour les snapshots et le rollback. */
    private val deepCopyFn: (DATA) -> DATA,

    /** Validateur optionnel résolu depuis l'annotation `@StoreValidator`. */
    private val validator: Validator<DATA>? = null
) : Store<DATA> {

    /** Indique si les données ont été chargées depuis un fichier ou générées par [defaultDataProvider]. */
    private enum class DataOrigin { FILE, DEFAULT }

    /** Objet de données vivant. Tout accès DOIT passer par [dataLock]. */
    @PublishedApi internal lateinit var _data: DATA

    override var data: DATA
        get() = dataLock.read { _data }
        set(newValue) {
            val operation = dataLock.write {
                val old = _data
                _data = newValue
                ReloadOperation(this::data, CapturedValue.DeepCopy(deepCopyFn(old)), CapturedValue.DeepCopy(deepCopyFn(newValue)))
            }
            onReloadCallbacks.forEach { it(operation) }
        }

    private val _dataOrigin: DataOrigin = if (path.exists()) DataOrigin.FILE else DataOrigin.DEFAULT

    /** Verrou lecture/écriture protégeant tous les accès à [_data]. */
    @PublishedApi internal val dataLock = ReentrantReadWriteLock()

    /** Snapshot de [_data] au dernier save. Sert à construire le [CapturedValue] old pour les callbacks de save. */
    @Volatile private var _lastSavedData: DATA? = null

    /** `true` après le premier appel réussi à [save]. */
    @Volatile private var _hasSavedAtLeastOnce: Boolean = false

    /** Chemin vers le fichier sidecar `.meta.json`. */
    private val metaPath: Path = path.resolveSibling("${path.fileName}.meta.json")

    override val meta : StoreMeta? = if(config.withMeta) if(path.exists() && metaPath.exists()) metaDecoder.invoke(metaPath) else StoreMeta() else null

    /** Passe à `true` à chaque update, quelle que soit la policy (voir [markDirty]) ; remis à `false` par le tick d'auto-save. Interne pour les tests. */
    @Volatile internal var isDirty = false

    /**
     * Marque les données modifiées : `meta.lastModified` et le drapeau dirty. Appelé par le pipeline d'update pour
     * TOUTES les policies, [UpdatePolicy.SKIP] compris : la persistance ne dépend pas de l'observation.
     */
    @PublishedApi
    internal fun markDirty() {
        if (config.withMeta) meta?.lastModified = Clock.System.now().formatLocal()
        isDirty = true
    }

    /** Quand `true`, les ticks d'auto-save sont ignorés. */
    private val autoSavePaused = AtomicBoolean(false)

    /** Handle vers la tâche planifiée d'auto-save (pour annulation). */
    private var autoSaveFuture: ScheduledFuture<*>? = null

    /** Scheduler single-thread pour l'auto-save périodique. */
    private val saveScheduler = Executors.newSingleThreadScheduledExecutor()

    // ── Callbacks ──
    override val onSaveCallbacks: MutableList<(Operation<DATA>) -> Unit> = mutableListOf()
    override val onReloadCallbacks: MutableList<(Operation<DATA>) -> Unit> = mutableListOf()
    override val onUpdateCallbacks: MutableList<(Operation<DATA>) -> Unit> = mutableListOf()
    override val onUpdateCallbacksMap: MutableMap<KProperty1<*, *>, MutableList<(Operation<DATA>) -> Unit>> = mutableMapOf<KProperty1<*, *>, MutableList<(Operation<DATA>) -> Unit>>()

    /** Politique d'update par propriété (défaut [UpdatePolicy.SNAPSHOT]). */
    @PublishedApi internal val updatePolicies: MutableMap<KProperty1<*, *>, UpdatePolicy> = mutableMapOf()

    /** Change la politique d'update d'une propriété au runtime. */
    @Suppress("unused")
    fun setUpdatePolicy(prop: KProperty1<*, *>, policy: UpdatePolicy) { updatePolicies[prop] = policy }

    /** Récupère la politique d'update d'une propriété. */
    @Suppress("unused")
    fun getUpdatePolicy(prop: KProperty1<*, *>): UpdatePolicy = updatePolicies[prop] ?: config.defaultUpdatePolicy

    init {
        initData()
        initUpdatePolicies()
        initValidation()
        initAutoSave()
        initShutdownHook()
    }

    /**
     * Charge les données depuis le fichier s'il existe, sinon crée depuis [defaultDataProvider].
     * Définit [_dataOrigin] et valide immédiatement les données par défaut (lance une exception si invalide).
     */
    private fun initData() {
        if (path.exists()) {
            _data = dataDecoder(path)
            _hasSavedAtLeastOnce = true
        } else {
            _data = defaultDataProvider.invoke()
            writeInitialFile()
        }
        _lastSavedData = deepCopyFn(_data)
    }

    /** Scanne les annotations [@StoreUpdatePolicy] sur les propriétés de [DATA] et ses classes imbriquées. */
    private fun initUpdatePolicies() {
        val visited = mutableSetOf<KClass<*>>()
        scanUpdatePolicies(_data::class, visited)
    }

    private fun scanUpdatePolicies(kClass: KClass<*>, visited: MutableSet<KClass<*>>) {
        if (!visited.add(kClass)) return
        val qn = kClass.qualifiedName
        if (qn != null && (qn.startsWith("kotlin.") || qn.startsWith("java."))) return

        kClass.memberProperties.forEach { prop ->
            prop.annotations
                .filterIsInstance<StoreUpdatePolicy>()
                .firstOrNull()
                ?.let { ann ->
                    val retClass = prop.returnType.classifier as? KClass<*>
                    if (ann.policy == UpdatePolicy.SHALLOW && retClass != null &&
                        (retClass.java.isPrimitive || retClass == String::class || retClass.java.isEnum)
                    ) {
//                        error("[Storify] @StoreUpdatePolicy(${UpdatePolicy.SHALLOW.name}) on '${prop.name}' is useless — " +
//                                "${retClass.simpleName} is immutable and already skips deep copy.")
                    }
                    updatePolicies[prop] = ann.policy
                }

            reachableClasses(prop.returnType).forEach { scanUpdatePolicies(it, visited) }
        }
    }

    private fun reachableClasses(type: KType): List<KClass<*>> {
        val result = mutableListOf<KClass<*>>()
        (type.classifier as? KClass<*>)?.let { result.add(it) }
        type.arguments.mapNotNull { it.type }.forEach { result.addAll(reachableClasses(it)) }
        return result
    }

    /**
     * Valide les données chargées/par défaut au démarrage.
     * Pour les données chargées depuis fichier, enrichit les erreurs avec les numéros de ligne JSON.
     * @throws ValidationException si les données sont invalides.
     */
    private fun initValidation() {
        if (!config.withValidation) return
        val result = runValidation(_data)
        if (result is ValidationResult.Failure) {
            val errors = if (_dataOrigin == DataOrigin.FILE) ValidationErrorEnricher.enrich(format, path, result.errors) else result.errors
            val source = if (_dataOrigin == DataOrigin.FILE) "loaded from file" else "default data"
            throw ValidationException(errors, "[Storify] Store '${path}' ($source) is invalid:\n${ValidationResult.Failure(errors).formatFull()}")
        }
    }

    /** Enregistre la tâche planifiée d'auto-save. Le marquage dirty, lui, vit dans le pipeline d'update : voir [markDirty]. */
    private fun initAutoSave() {
        if (!config.withAutoSave) return

        autoSaveFuture = saveScheduler.scheduleAtFixedRate({
            try {
                if (autoSavePaused.get()) {
                    log.debug("[Storify] Auto-save skipped (paused)"); return@scheduleAtFixedRate
                }
                if (!isDirty) {
                    log.debug("[Storify] Auto-save skipped (no changes)"); return@scheduleAtFixedRate
                }
                isDirty = false
                save(SaveTrigger.AUTO_SAVE)
            } catch (e: Exception) {
                log.error("[Storify] Auto-save failed", e)
            }
        }, config.autoSaveIntervalMs, config.autoSaveIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** Garantit la persistance des données à l'arrêt de la JVM. */
    private fun initShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            autoSaveFuture?.cancel(false)
            save(SaveTrigger.SHUTDOWN)
        })
    }

    // ── Persistance ──
    /**
     * Persiste [_data] sur disque, met à jour le snapshot [_lastSavedData],
     * écrit le sidecar meta, et déclenche les [onSaveCallbacks] **hors du lock**.
     */
    private fun save(trigger: SaveTrigger) {
        val operation: Operation<DATA> = dataLock.read {

            val oldCaptured: CapturedValue<DATA> = when {
                !_hasSavedAtLeastOnce && _lastSavedData != null -> CapturedValue.Initial(_lastSavedData!!)
                _lastSavedData != null -> CapturedValue.DeepCopy(_lastSavedData!!)
                else -> CapturedValue.Unavailable
            }

            dataEncoder.invoke(_data, path)

            _lastSavedData = if (config.useDeepCopy) deepCopyFn(_data) else null
            _hasSavedAtLeastOnce = true

            val newCaptured: CapturedValue<DATA> =
                if (_lastSavedData != null) CapturedValue.DeepCopy(_lastSavedData!!)
                else CapturedValue.Unavailable

            if (config.withMeta) metaEncoder.invoke(meta!!, metaPath)
            SaveOperation(oldCaptured, newCaptured, trigger)
        }
        onSaveCallbacks.forEach { it(operation) }
    }
    override fun saveImmediate() = save(SaveTrigger.IMMEDIATE)
    override fun reloadFromFile() { data = this.dataDecoder(path) }

    // ── Contrôle auto-save ──
    override fun pauseAutoSave() {
        autoSavePaused.set(true)
        log.info("[Storify] Auto-save PAUSED")
    }
    override fun resumeAutoSave() {
        autoSavePaused.set(false)
        log.info("[Storify] Auto-save RESUMED")
    }
    override fun isAutoSavePaused(): Boolean = autoSavePaused.get()

    // ── Enregistrement de callbacks ──
    override fun registerOnSave(callback: (Operation<DATA>) -> Unit) { onSaveCallbacks.add(callback) }
    override fun registerOnReload(callback: (Operation<DATA>) -> Unit) { onReloadCallbacks.add(callback) }
    override fun registerOnUpdate(callback: (Operation<DATA>) -> Unit) { onUpdateCallbacks.add(callback) }
    override fun registerOnUpdateOn(prop: KProperty1<*, *>, callback: (Operation<DATA>) -> Unit) {
        onUpdateCallbacksMap.compute(prop) { _, value ->
            if (value == null) mutableListOf(callback) else {
                value.add(callback); value
            }
        }
    }

    /**
     * Résultat interne d'un [runUpdateInternal].
     * Contient l'opération capturée (snapshots old/new) et la propriété cible,
     * prêts à être dispatchés aux callbacks **hors du lock**.
     */
    @PublishedApi internal data class UpdateOutcome<DATA : Any>(
        val success: Boolean,
        val operation: Operation<DATA>,
        val prop: KProperty1<*, *>
    )

    /** Exécute le [validator] sur [data] et retourne un [ValidationResult]. */
    private fun runValidation(data: DATA): ValidationResult {
        if (validator != null) {
            val ctx = ValidationContext(
                currentPath = data::class.simpleName ?: "Unknown",
                currentClassName = data::class.simpleName ?: "Unknown"
            )
            validator.validate(data, ctx)
            return if (ctx.hasErrors) ValidationResult.Failure(ctx.errors) else ValidationResult.Success
        }
        return ValidationResult.Success
    }

    /**
     * Dispatche une [UpdateOutcome] aux callbacks enregistrés.
     * Skipé si [outcome] est `null` (policy [UpdatePolicy.SKIP]).
     * **Doit être appelé hors du [dataLock].**
     */
    @PublishedApi internal fun dispatchUpdateCallbacks(outcome: UpdateOutcome<DATA>) {
        onUpdateCallbacks.forEach { it(outcome.operation) }
        onUpdateCallbacksMap[outcome.prop]?.forEach { it(outcome.operation) }
    }

    /**
     * Pipeline central d'update.
     * TODO refaire cette docs
     * Flux :
     * 1. Acquiert le write lock
     * 2. Consulte la [UpdatePolicy] de la propriété
     * 3. Si policy ≠ SKIP/SNAPSHOT et [useDeepCopy] : deep-copy la valeur du champ **avant** mutation
     * 4. Applique la mutation directement sur [_data]
     * 5. Capture old/new comme [CapturedValue] selon la policy
     *
     * Les callbacks ne sont **pas** appelés ici — c'est l'appelant qui dispatche
     * le [UpdateOutcome] retourné via [dispatchUpdateCallbacks].
     */
    @PublishedApi
    internal inline fun <RECEIVER : Any, reified VALUE> runUpdateInternal(
        kProperty1: KProperty1<RECEIVER, VALUE>,
        noinline getReceiver: DATA.() -> RECEIVER,
        noinline applyUpdate: (RECEIVER) -> Unit,
        createOperation: (old: CapturedValue<VALUE>, new: CapturedValue<VALUE>) -> Operation<DATA>
    ): UpdateOutcome<DATA>? = dataLock.write {

        val receiver = _data.getReceiver()

        val policy = updatePolicies[kProperty1] ?: config.defaultUpdatePolicy

        if (policy == UpdatePolicy.SKIP) {
            applyUpdate(receiver)
            markDirty()
            return@write null
        }

        val immutable = VALUE::class.java.isPrimitive || VALUE::class == String::class || VALUE::class.java.isEnum

        val wantSnapshot = policy == UpdatePolicy.SNAPSHOT && config.useDeepCopy && !immutable

        val oldValue = kProperty1.get(receiver)
        val oldSnapshot: VALUE = if (wantSnapshot) deepCopyValue(oldValue) else oldValue

        applyUpdate(receiver)
        markDirty()

        val newValue = kProperty1.get(receiver)
        val oldCaptured: CapturedValue<VALUE> = when {
            wantSnapshot -> CapturedValue.DeepCopy(oldSnapshot)
            oldValue !== newValue -> CapturedValue.Shallow(oldValue)
            else -> CapturedValue.Unavailable
        }
        val newCaptured: CapturedValue<VALUE> = when {
            wantSnapshot -> CapturedValue.DeepCopy(deepCopyValue(newValue))
            else -> CapturedValue.Shallow(newValue)
        }

        val operation = createOperation(oldCaptured, newCaptured)
        UpdateOutcome(true, operation, kProperty1)
    }

    @PublishedApi
    internal inline fun <reified RECEIVER : Any, reified VALUE> setInternal(kMutableProperty: KMutableProperty1<RECEIVER, VALUE>, newValue: VALUE, noinline getReceiver: DATA.() -> RECEIVER) {
        val outcome = runUpdateInternal(
            kMutableProperty, getReceiver,
            applyUpdate = { receiver -> kMutableProperty.set(receiver, newValue) },
            createOperation = { old, new -> SetOperation(kMutableProperty, old, new) }
        )
        if(outcome != null) dispatchUpdateCallbacks(outcome) else return
    }

    @PublishedApi
    internal inline fun <reified RECEIVER : Any, reified VALUE : Any> mutateInternal(kProperty1: KProperty1<RECEIVER, VALUE>, noinline getReceiver: DATA.() -> RECEIVER, noinline updateObject: (VALUE) -> Unit) {
        val outcome = runUpdateInternal(
            kProperty1, getReceiver,
            applyUpdate = { receiver -> updateObject(kProperty1.get(receiver)) },
            createOperation = { old, new -> MutateOperation(kProperty1, old, new) }
        )
        if(outcome != null) dispatchUpdateCallbacks(outcome) else return
    }

    fun transactionInternal(block: DATA.() -> Unit) {
        val operation: Operation<DATA> = dataLock.write {
            val backupSnapshot: DATA? = if (config.useDeepCopy) deepCopyFn(_data) else null

            try {
                _data.block()
                markDirty()

                if (config.useDeepCopy && backupSnapshot != null) {
                    val o = CapturedValue.DeepCopy(backupSnapshot)
                    val n = CapturedValue.DeepCopy(deepCopyFn(_data))
                    TransactionOperation(o, n)
                } else TransactionOperation(CapturedValue.Unavailable, CapturedValue.Shallow(_data))

            } catch (e: Exception) {
                log.warn("[Storify] Transaction failed with exception — rolled back: {}", e.message)
                if (backupSnapshot != null) _data = backupSnapshot
                throw e
            }
        }
        onUpdateCallbacks.forEach { it(operation) }
    }

    /** Écrit le fichier initial quand aucun fichier n'existait (premier lancement). */
    private fun writeInitialFile() = dataLock.read { dataEncoder.invoke(_data, path) }

}
