package fr.moulou.storify.core

import fr.moulou.storify.*
import fr.moulou.storify.utils.DateUtils.formatLocal
import fr.moulou.storify.utils.deepCopyValue
import fr.moulou.storify.validation.*
import kotlinx.serialization.KSerializer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createDirectories
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
 * @property withValidation    Active la validation au chargement initial (fichier ou données par défaut). Défaut `true` (C-24) : sans validator
 *                             elle ne coûte rien, et poser un validator c'est vouloir qu'il tourne ; `false` est l'échappatoire explicite.
 * @property withAutoSave      Persiste automatiquement les données modifiées sur disque. Défaut `true`.
 * @property withMeta          Gère un fichier sidecar `.meta.json` (lastModified, etc.). Défaut `false`.
 * @property useDeepCopy       Deep-copy les données pour capturer old/new dans les callbacks et permettre
 *                             le rollback des transactions en cas d'exception. Défaut `true`.
 *                             Désactiver améliore les performances mais les snapshots old seront indisponibles.
 * @property defaultUpdatePolicy Politique d'update par défaut pour les propriétés sans annotation
 *                               [StoreUpdatePolicy]. Défaut [UpdatePolicy.SKIP] : sans policy explicite, les callbacks
 *                               se taisent. La persistance (marquage dirty), elle, est garantie pour toutes les policies.
 * @property autoSaveIntervalMs  Intervalle en millisecondes entre chaque tick d'auto-save. Défaut 5 min.
 * @property validateOnUpdate  Valide la racine à CHAQUE update, avec rollback et [ValidationFailedOperation] en échec.
 *                             NON RECOMMANDÉ : copie de la racine entière et validator sous write lock à chaque geste ;
 *                             préférez des contrôles métier avant de muter. Exige [useDeepCopy]. Défaut `false`.
 */
data class StoreConfig(
    val withValidation: Boolean = true,
    val withAutoSave: Boolean = true,
    val withMeta: Boolean = false,
    val useDeepCopy: Boolean = true,
    val defaultUpdatePolicy: UpdatePolicy = UpdatePolicy.SKIP,
    val autoSaveIntervalMs: Long = 300_000L,
    val validateOnUpdate: Boolean = false
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
class BaseStore<DATA : Any> @PublishedApi internal constructor(
    override val path: Path,
    override val format: StoreFormat,

    /** Options de comportement du store. */
    @PublishedApi internal val config: StoreConfig,

    /** Le sérialiseur de [DATA], matérialisé une fois pour toutes au site réifié de la factory (C-09). */
    private val dataSerializer: KSerializer<DATA>,

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
    @PublishedApi
    internal lateinit var _data: DATA

    override val data: DATA
        get() = dataLock.read { _data }

    /** Remplace la racine sous write lock et notifie onReload : la voie interne de [reloadFromFile], le remplacement de racine n'est pas offert aux consommateurs (C-08). */
    internal fun replaceData(newValue: DATA) {
        val operation = dataLock.write {
            val old = _data
            _data = newValue
            ReloadOperation(this::data, CapturedValue.DeepCopy(deepCopyFn(old)), CapturedValue.DeepCopy(deepCopyFn(newValue)))
        }
        onReloadCallbacks.forEach { it(operation) }
    }

    private val _dataOrigin: DataOrigin = if (path.exists()) DataOrigin.FILE else DataOrigin.DEFAULT

    /** Verrou lecture/écriture protégeant tous les accès à [_data]. */
    @PublishedApi
    internal val dataLock = ReentrantReadWriteLock()

    /** Snapshot de [_data] au dernier save. Sert à construire le [CapturedValue] old pour les callbacks de save. */
    @Volatile
    private var _lastSavedData: DATA? = null

    /** `true` après le premier appel réussi à [save]. */
    @Volatile
    private var _hasSavedAtLeastOnce: Boolean = false

    /** Chemin vers le fichier sidecar `.meta.json`. */
    private val metaPath: Path = path.resolveSibling("${path.fileName}.meta.json")

    override val meta: StoreMeta? = if (config.withMeta) if (path.exists() && metaPath.exists()) metaFormat.decodeFromPath(StoreMeta.serializer(), metaPath) else StoreMeta() else null

    /** Passe à `true` à chaque update, quelle que soit la policy (voir [markDirty]) ; remis à `false` par le tick d'auto-save. Interne pour les tests. */
    @Volatile
    internal var isDirty = false

    /**
     * Marque les données modifiées : `meta.lastModified` et le drapeau dirty. Appelé par le pipeline d'update pour
     * TOUTES les policies, [UpdatePolicy.SKIP] compris : la persistance ne dépend pas de l'observation.
     */
    @PublishedApi
    internal fun markDirty() {
        if (config.withMeta) meta?.lastModified = Clock.System.now().formatLocal()
        isDirty = true
    }

    // ── Les aides du garde C-05 (validateOnUpdate), appelées depuis le pipeline inline ──

    /** La copie de sécurité de la racine, pour le rollback du garde. */
    @PublishedApi
    internal fun rootBackup(): DATA = deepCopyFn(_data)

    /** Rend l'échec de validation de la racine, ou null si tout est valide. */
    @PublishedApi
    internal fun guardValidationFailure(): ValidationResult.Failure? = runValidation(_data) as? ValidationResult.Failure

    /** Restaure la racine depuis la copie de sécurité du garde. */
    @PublishedApi
    internal fun restoreRoot(backup: DATA) {
        _data = backup
    }

    /** Quand `true`, les ticks d'auto-save sont ignorés. */
    private val autoSavePaused = AtomicBoolean(false)

    /** Handle vers la tâche planifiée d'auto-save (pour annulation). */
    private var autoSaveFuture: ScheduledFuture<*>? = null

    /** Scheduler single-thread pour l'auto-save périodique. */
    private val saveScheduler = Executors.newSingleThreadScheduledExecutor()

    /** Verrou d'IO : les sauvegardes d'un même store s'exécutent l'une après l'autre. */
    private val saveIoLock = Any()

    /** `true` après [close] : le store reste lisible, les écritures refusent. */
    private val closed = AtomicBoolean(false)

    override val isClosed: Boolean get() = closed.get()

    /** Le hook d'arrêt JVM, gardé en champ pour que [close] puisse le désarmer. */
    private val shutdownHook = Thread { runShutdownHook() }

    /** Le corps du hook, testable sans éteindre la JVM : annule le tick en vol et, comme la sauvegarde d'adieu de [close], ne sauve que dirty (C-23). */
    internal fun runShutdownHook() {
        autoSaveFuture?.cancel(false)
        if (isDirty) save(SaveTrigger.SHUTDOWN)
    }

    /** Refuse toute écriture sur un store fermé. */
    @PublishedApi
    internal fun checkOpen() {
        check(!closed.get()) { "[Storify] Store '$path' is closed" }
    }

    // ── Callbacks : conteneurs privés et thread-safe, l'enregistrement passe par register* (C-08) ──
    private val onSaveCallbacks = CopyOnWriteArrayList<(Operation<DATA>) -> Unit>()
    private val onReloadCallbacks = CopyOnWriteArrayList<(Operation<DATA>) -> Unit>()
    private val onUpdateCallbacks = CopyOnWriteArrayList<(Operation<DATA>) -> Unit>()
    private val onUpdateCallbacksMap = ConcurrentHashMap<KProperty1<*, *>, CopyOnWriteArrayList<(Operation<DATA>) -> Unit>>()

    /** Politique d'update par propriété ; à défaut d'entrée ici, celle de [StoreConfig.defaultUpdatePolicy] s'applique. */
    @PublishedApi
    internal val updatePolicies: MutableMap<KProperty1<*, *>, UpdatePolicy> = mutableMapOf()

    /** Change la politique d'update d'une propriété au runtime. */
    @Suppress("unused")
    fun setUpdatePolicy(prop: KProperty1<*, *>, policy: UpdatePolicy) {
        updatePolicies[prop] = policy
    }

    /** Récupère la politique d'update d'une propriété. */
    @Suppress("unused")
    fun getUpdatePolicy(prop: KProperty1<*, *>): UpdatePolicy = updatePolicies[prop] ?: config.defaultUpdatePolicy

    init {
        require(!config.validateOnUpdate || config.useDeepCopy) { "[Storify] validateOnUpdate requires useDeepCopy (root rollback)" }
        initData()
        initUpdatePolicies()
        initValidation()
        persistInitialData()
        initAutoSave()
        initShutdownHook()
    }

    /**
     * Charge les données depuis le fichier s'il existe, sinon les crée depuis [defaultDataProvider],
     * sans rien écrire : la validation passe d'abord, le fichier initial vient après ([persistInitialData], C-06).
     */
    private fun initData() {
        sweepOrphanTemps()
        if (path.exists()) {
            _data = format.decodeFromPath(dataSerializer, path)
            _hasSavedAtLeastOnce = true
        } else {
            _data = defaultDataProvider.invoke()
        }
        _lastSavedData = deepCopyFn(_data)
    }

    /** Écrit le fichier initial des données nées par défaut, la validation étant passée : des défauts invalides ne touchent jamais le disque (C-06). */
    private fun persistInitialData() {
        if (_dataOrigin == DataOrigin.DEFAULT) writeInitialFile()
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
     * Valide les données chargées ou nées par défaut au démarrage.
     * Enrichit les erreurs des numéros de ligne JSON dès qu'un fichier existe : chargé, ou copié depuis une ressource (C-06).
     * @throws ValidationException si les données sont invalides.
     */
    private fun initValidation() {
        if (!config.withValidation) return
        val result = runValidation(_data)
        if (result is ValidationResult.Failure) {
            val errors = if (path.exists()) ValidationErrorEnricher.enrich(format, path, result.errors) else result.errors
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
                save(SaveTrigger.AUTO_SAVE)
            } catch (e: Exception) {
                log.error("[Storify] Auto-save failed", e)
            }
        }, config.autoSaveIntervalMs, config.autoSaveIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** Arme le filet anti-crash : la persistance à l'arrêt de la JVM, tant que le store n'est pas fermé. */
    private fun initShutdownHook() {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    // ── Persistance ──
    /**
     * Persiste [_data] sur disque, met à jour le snapshot [_lastSavedData],
     * écrit le sidecar meta, et déclenche les [onSaveCallbacks] **hors du lock**.
     */
    private fun save(trigger: SaveTrigger) {
        if (closed.get()) {
            when (trigger) {
                SaveTrigger.IMMEDIATE -> throw IllegalStateException("[Storify] Store '$path' is closed")
                SaveTrigger.AUTO_SAVE -> return // un tick en vol pendant la fermeture s'éteint sans bruit
                else -> {} // CLOSE et SHUTDOWN : les sauvegardes de fin de vie passent
            }
        }
        val operation: Operation<DATA> = dataLock.read {

            val oldCaptured: CapturedValue<DATA> = when {
                !_hasSavedAtLeastOnce && _lastSavedData != null -> CapturedValue.Initial(_lastSavedData!!)
                _lastSavedData != null -> CapturedValue.DeepCopy(_lastSavedData!!)
                else -> CapturedValue.Unavailable
            }

            synchronized(saveIoLock) {
                atomicWrite(path) { temp -> format.encodeToPath(dataSerializer, _data, temp) }
                if (config.withMeta) atomicWrite(metaPath) { temp -> metaFormat.encodeToPath(StoreMeta.serializer(), meta!!, temp) }
            }
            isDirty = false // après une écriture réussie seulement : un échec laisse le dirty au prochain tick

            _lastSavedData = if (config.useDeepCopy) deepCopyFn(_data) else null
            _hasSavedAtLeastOnce = true

            val newCaptured: CapturedValue<DATA> =
                if (_lastSavedData != null) CapturedValue.DeepCopy(_lastSavedData!!)
                else CapturedValue.Unavailable

            SaveOperation(oldCaptured, newCaptured, trigger)
        }
        onSaveCallbacks.forEach { it(operation) }
    }

    override fun saveImmediate() = save(SaveTrigger.IMMEDIATE)

    override fun reloadFromFile(validate: Boolean) {
        checkOpen()
        val incoming = format.decodeFromPath(dataSerializer, path)
        if (validate && config.withValidation) {
            val result = runValidation(incoming)
            if (result is ValidationResult.Failure) {
                val errors = ValidationErrorEnricher.enrich(format, path, result.errors)
                throw ValidationException(errors, "[Storify] Reload of '$path' rejected, in-memory data untouched:\n${ValidationResult.Failure(errors).formatFull()}")
            }
        }
        replaceData(incoming)
    }

    override fun validateNow(): ValidationResult = dataLock.read { runValidation(_data) }

    // ── Contrôle auto-save ──
    override fun pauseAutoSave() {
        if (closed.get()) return
        autoSavePaused.set(true)
        log.info("[Storify] Auto-save PAUSED")
    }

    override fun resumeAutoSave() {
        if (closed.get()) return
        autoSavePaused.set(false)
        log.info("[Storify] Auto-save RESUMED")
    }

    override fun isAutoSavePaused(): Boolean = autoSavePaused.get()

    // ── Fin de vie ──
    /**
     * Détache proprement le store : le tick d'auto-save est annulé, le planificateur arrêté (la JVM n'est
     * plus retenue), le hook d'arrêt JVM désarmé, et les données encore dirty font une sauvegarde d'adieu
     * ([SaveTrigger.CLOSE]). Idempotent. Un store fermé reste lisible ; toute écriture lève une [IllegalStateException].
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        autoSaveFuture?.cancel(false)
        saveScheduler.shutdown()
        try {
            if (!saveScheduler.awaitTermination(5, TimeUnit.SECONDS)) log.warn("[Storify] Auto-save scheduler of '{}' did not stop within 5s", path)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val hookRemoved = try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            false // la JVM s'éteint déjà : le hook fait ou fera la sauvegarde, inutile de doubler
        }

        if (hookRemoved && isDirty) save(SaveTrigger.CLOSE)
        log.info("[Storify] Store '{}' closed.", path)
    }

    // ── Enregistrement de callbacks ──
    override fun registerOnSave(callback: (Operation<DATA>) -> Unit) {
        onSaveCallbacks.add(callback)
    }

    override fun registerOnReload(callback: (Operation<DATA>) -> Unit) {
        onReloadCallbacks.add(callback)
    }

    override fun registerOnUpdate(callback: (Operation<DATA>) -> Unit) {
        if (config.defaultUpdatePolicy == UpdatePolicy.SKIP && updatePolicies.isEmpty()) {
            log.warn("[Storify] Update callback registered but defaultUpdatePolicy is SKIP and no property carries a policy: it will stay silent (annotate @StoreUpdatePolicy, set defaultUpdatePolicy, or call setUpdatePolicy)")
        }
        onUpdateCallbacks.add(callback)
    }

    override fun registerOnUpdateOn(prop: KProperty1<*, *>, callback: (Operation<DATA>) -> Unit) {
        if (getUpdatePolicy(prop) == UpdatePolicy.SKIP) {
            log.warn("[Storify] Update callback registered on '{}' but its effective policy is SKIP: it will stay silent (annotate @StoreUpdatePolicy, set defaultUpdatePolicy, or call setUpdatePolicy)", prop.name)
        }
        onUpdateCallbacksMap.computeIfAbsent(prop) { CopyOnWriteArrayList() }.add(callback)
    }

    /**
     * Résultat interne d'un [runUpdateInternal].
     * Contient l'opération capturée (snapshots old/new) et la propriété cible,
     * prêts à être dispatchés aux callbacks **hors du lock**.
     */
    @PublishedApi
    internal data class UpdateOutcome<DATA : Any>(
        val success: Boolean,
        val operation: Operation<DATA>,
        val prop: KProperty1<*, *>
    )

    /** Exécute le [validator] sur [data] et retourne un [ValidationResult]. */
    @PublishedApi
    internal fun runValidation(data: DATA): ValidationResult {
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
    @PublishedApi
    internal fun dispatchUpdateCallbacks(outcome: UpdateOutcome<DATA>) {
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
        checkOpen()

        val receiver = _data.getReceiver()

        val policy = updatePolicies[kProperty1] ?: config.defaultUpdatePolicy

        // C-05, opt-in validateOnUpdate : copie de sécurité de la racine (seul rollback générique d'une
        // mutation en place) et capture de l'avant, pour l'opération d'échec.
        val guardBackup: DATA? = if (config.validateOnUpdate) rootBackup() else null
        val guardOld: CapturedValue<VALUE> = if (guardBackup != null) CapturedValue.DeepCopy(deepCopyValue(kProperty1.get(receiver))) else CapturedValue.Unavailable

        if (policy == UpdatePolicy.SKIP) {
            applyUpdate(receiver)
            if (guardBackup != null) {
                val failure = guardValidationFailure()
                if (failure != null) {
                    val attempted = CapturedValue.DeepCopy(deepCopyValue(kProperty1.get(receiver)))
                    restoreRoot(guardBackup)
                    return@write UpdateOutcome(false, ValidationFailedOperation(kProperty1, attempted, guardOld, failure.formatFull()), kProperty1)
                }
            }
            markDirty()
            return@write null
        }

        val immutable = VALUE::class.java.isPrimitive || VALUE::class == String::class || VALUE::class.java.isEnum

        val wantSnapshot = policy == UpdatePolicy.SNAPSHOT && config.useDeepCopy && !immutable

        val oldValue = kProperty1.get(receiver)
        val oldSnapshot: VALUE = if (wantSnapshot) deepCopyValue(oldValue) else oldValue

        applyUpdate(receiver)
        if (guardBackup != null) {
            val failure = guardValidationFailure()
            if (failure != null) {
                val attempted = CapturedValue.DeepCopy(deepCopyValue(kProperty1.get(receiver)))
                restoreRoot(guardBackup)
                return@write UpdateOutcome(false, ValidationFailedOperation(kProperty1, attempted, guardOld, failure.formatFull()), kProperty1)
            }
        }
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
        if (outcome != null) dispatchUpdateCallbacks(outcome) else return
    }

    @PublishedApi
    internal inline fun <reified RECEIVER : Any, reified VALUE : Any> mutateInternal(kProperty1: KProperty1<RECEIVER, VALUE>, noinline getReceiver: DATA.() -> RECEIVER, noinline updateObject: (VALUE) -> Unit) {
        val outcome = runUpdateInternal(
            kProperty1, getReceiver,
            applyUpdate = { receiver -> updateObject(kProperty1.get(receiver)) },
            createOperation = { old, new -> MutateOperation(kProperty1, old, new) }
        )
        if (outcome != null) dispatchUpdateCallbacks(outcome) else return
    }

    @PublishedApi
    internal fun transactionInternal(block: DATA.() -> Unit) {
        checkOpen()
        val operation: Operation<DATA> = dataLock.write {
            val backupSnapshot: DATA? = if (config.useDeepCopy) deepCopyFn(_data) else null

            try {
                _data.block()

                // C-05, opt-in : la transaction se valide en bloc ; en échec, tout est restauré.
                if (config.validateOnUpdate) {
                    val result = runValidation(_data)
                    if (result is ValidationResult.Failure && backupSnapshot != null) {
                        _data = backupSnapshot
                        return@write TransactionOperation(CapturedValue.DeepCopy(backupSnapshot), CapturedValue.Unavailable, success = false, validationError = result.formatFull())
                    }
                }
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
    private fun writeInitialFile() = dataLock.read { atomicWrite(path) { temp -> format.encodeToPath(dataSerializer, _data, temp) } }

    // ── Écriture atomique ──
    /**
     * Écrit via un fichier temporaire unique et voisin, force le flush disque, puis remplace la cible par
     * déplacement atomique : elle est toujours une version entière, un crash en pleine écriture ne la touche jamais.
     */
    private fun atomicWrite(target: Path, encodeTo: (Path) -> Unit) {
        target.toAbsolutePath().parent?.createDirectories() // la leçon C-04, garantie ici pour tout format, tiers compris
        val temp = target.resolveSibling("${target.fileName}.${UUID.randomUUID().toString().substring(0, 8)}.tmp")
        try {
            encodeTo(temp)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                log.warn("[Storify] Atomic move unsupported for '{}': falling back to a non-atomic replace", target)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    /** Balaye les temporaires orphelins d'un crash passé (motif strict : ceux de ce fichier et de son sidecar). */
    private fun sweepOrphanTemps() {
        val directory = path.toAbsolutePath().parent ?: return
        if (!directory.exists()) return
        val prefix = "${path.fileName}."
        runCatching {
            Files.newDirectoryStream(directory) { candidate ->
                val name = candidate.fileName.toString()
                name.startsWith(prefix) && name.endsWith(".tmp")
            }.use { stream -> stream.forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    private companion object {
        /** Le format du sidecar meta : toujours JSON, comme son nom `.meta.json` le promet, quel que soit le format du store (C-09). */
        val metaFormat = JsonFormat()
    }

}
