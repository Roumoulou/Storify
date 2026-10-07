// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.core

import fr.moulou.storify.*
import fr.moulou.storify.utils.AtomicFiles
import fr.moulou.storify.utils.DeepCopier
import fr.moulou.storify.validation.*
import kotlinx.serialization.KSerializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties

/**
 * Le store : l'attelage d'une data class sérialisable, d'un fichier et de tout ce qu'un mod réclame autour, mécanisme par mécanisme dans
 * `Docs\architecture.md` (le cycle de vie au chapitre 4). Le corps de la classe suit l'ordre de Java : le companion, les propriétés du public au
 * privé, `init`, puis les méthodes dans le sens de la vie d'un store (l'ouverture, la lecture, les mises à jour, les callbacks, les policies,
 * la persistance, la fin de vie, les aides communes), l'appelé sous l'appelant, et les types imbriqués en dernier.
 *
 * Les invariants :
 * - tout accès à [_data] passe par [dataLock] : `data` et les sauvegardes sous le read lock, les mises à jour et le remplacement de racine
 *   sous le write lock ;
 * - les sauvegardes d'un même store s'exécutent l'une après l'autre, sous [saveIoLock] ;
 * - les callbacks sont notifiés hors de tout verrou, avec des captures, jamais des références prises sous verrou (hors `Shallow`) ;
 * - le drapeau dirty est posé par le pipeline d'update pour toutes les policies, et remis à zéro après une écriture réussie, quel que soit
 *   le déclencheur, ou après un rechargement.
 *
 * @param DATA La data class `@Serializable` gérée par ce store.
 */
class BaseStore<DATA : Any> internal constructor(
    storePath: Path,
    override val format: StoreFormat,

    /** Options de comportement du store. */
    internal val config: StoreConfig,

    /** Le sérialiseur de [DATA], matérialisé une fois pour toutes au site réifié de la factory (C-09). */
    private val dataSerializer: KSerializer<DATA>,

    /** Factory fournissant la [DATA] par défaut quand aucun fichier n'existe. */
    private val defaultDataProvider: () -> DATA,

    /** Validateur optionnel résolu depuis l'annotation `@StoreValidator`. */
    private val validator: Validator<DATA>? = null
) : Store<DATA> {

    private companion object {
        /** Le format du sidecar meta : toujours JSON, comme son nom `.meta.json` le promet, quel que soit le format du store (C-09). */
        val metaFormat = JsonFormat()
    }

    // ── L'état ── les propriétés, du public au privé

    /** Le chemin du store, absolu et normalisé dès la construction (C-12) : logs, erreurs, sidecar et temporaires en héritent tous. */
    override val path: Path = storePath.toAbsolutePath().normalize()

    override val data: DATA get() = dataLock.read { _data }

    /** Le sidecar meta, lu à l'ouverture s'il existe, neuf sinon, `null` sans `withMeta` ; posé dans `init`, après les chemins dont il dépend. */
    override val meta: StoreMeta?

    override val isClosed: Boolean get() = closed.get()

    override val isReadOnly: Boolean get() = config.readOnly

    /** Le nom du logger du store, pour les tests. */
    internal val loggerName: String get() = log.name

    /** Le copieur profond du store, celui de son format (C-29) : toute copie de racine ou de valeur passe par lui. */
    internal val copier: DeepCopier = format.deepCopier()

    /** Objet de données vivant, le champ de secours de [data]. Tout accès DOIT passer par [dataLock]. */
    internal lateinit var _data: DATA

    /** Passe à `true` à chaque update, quelle que soit la policy (voir [markDirty]) ; remis à `false` après toute écriture réussie, quel que soit le déclencheur, et au rechargement (C-47). Interne pour les tests. */
    @Volatile internal var isDirty = false

    /** `true` quand le tick d'auto-save est planifié. Interne pour les tests (C-30). */
    internal val isAutoSaveScheduled: Boolean get() = autoSaveFuture != null

    /** `true` quand le hook est enregistré auprès de la JVM : [close] ne désarme que lui, et les tests le lisent (C-30). */
    internal val isShutdownHookArmed: Boolean = config.withShutdownHook && !config.readOnly

    /** Le logger du store, fabriqué une fois (C-11), au nom que la config donne (C-37) : celui du mod range les lignes sous son journal. */
    private val log: Logger = LoggerFactory.getLogger(config.loggerName)

    /** Chemin vers le fichier sidecar `.meta.json`. */
    private val metaPath: Path = path.resolveSibling("${path.fileName}.meta.json")

    /** Verrou lecture/écriture protégeant tous les accès à [_data]. */
    private val dataLock = ReentrantReadWriteLock()

    /** L'origine des données, posée par [initData] une fois le fournisseur appelé : lui seul sait s'il a posé un fichier (C-40). */
    private var dataOrigin: DataOrigin = DataOrigin.DEFAULT

    // La persistance

    /**
     * Snapshot de [_data] au dernier save : le `old` du prochain callback de save. Tenu seulement devant un auditeur de save (C-41) : pris à
     * l'arrivée du premier ([registerOnSave]), puis à chaque save ; sans auditeur il reste `null`, et rien ne se copie.
     */
    @Volatile
    private var lastSavedData: DATA? = null

    /** `true` après le premier appel réussi à [save]. */
    @Volatile
    private var hasSavedAtLeastOnce: Boolean = false

    /** Verrou d'IO : les sauvegardes d'un même store s'exécutent l'une après l'autre. */
    private val saveIoLock = Any()

    // L'auto-save

    /** Scheduler single-thread pour l'auto-save périodique. */
    private val saveScheduler = Executors.newSingleThreadScheduledExecutor()

    /** Handle vers la tâche planifiée d'auto-save (pour annulation). */
    private var autoSaveFuture: ScheduledFuture<*>? = null

    /** Quand `true`, les ticks d'auto-save sont ignorés. */
    private val autoSavePaused = AtomicBoolean(false)

    // La fin de vie

    /** `true` après [close] : le store reste lisible, les écritures refusent. */
    private val closed = AtomicBoolean(false)

    /** Le hook d'arrêt JVM, gardé en champ pour que [close] puisse le désarmer. */
    private val shutdownHook = Thread { runShutdownHook() }

    // Les callbacks : conteneurs privés et thread-safe, l'enregistrement passe par register* (C-08)

    private val onSaveCallbacks = CopyOnWriteArrayList<(Operation<DATA>) -> Unit>()
    private val onReloadCallbacks = CopyOnWriteArrayList<(Operation<DATA>) -> Unit>()
    private val onUpdateCallbacks = CopyOnWriteArrayList<(Operation<DATA>) -> Unit>()
    private val onUpdateCallbacksMap = ConcurrentHashMap<KProperty1<*, *>, CopyOnWriteArrayList<TargetedListener<DATA>>>()

    // Les policies

    /**
     * Politique d'update par propriété ; à défaut d'entrée ici, celle de [StoreConfig.defaultUpdatePolicy] s'applique. Thread-safe (C-45) :
     * [setUpdatePolicy] y écrit hors de tout verrou pendant que le pipeline d'update la lit.
     */
    private val updatePolicies = ConcurrentHashMap<KProperty1<*, *>, UpdatePolicy>()

    /** Les propriétés de l'arbre de DATA, collectées par le scan des policies : la garde de [setUpdatePolicy]. */
    private val dataTreeProperties = mutableSetOf<KProperty1<*, *>>()

    init {
        meta = when {
            !config.withMeta -> null
            path.exists() && metaPath.exists() -> metaFormat.decodeFile(StoreMeta.serializer(), metaPath)
            else -> StoreMeta()
        }
        require(!config.validateOnUpdate || config.useDeepCopy) { "[Storify] validateOnUpdate requires useDeepCopy (root rollback)" }
        initData()
        initUpdatePolicies()
        initValidation()
        persistInitialData()
        initAutoSave()
        initShutdownHook()
        if (config.readOnly) log.info("[Storify] Store '{}' opened read-only", path)
    }

    /**
     * Charge les données depuis le fichier s'il existe, sinon les demande à [defaultDataProvider]. Les défauts du code n'écrivent rien ici :
     * la validation passe d'abord, le fichier initial vient après ([persistInitialData], C-06). Le fournisseur d'une ressource embarquée,
     * lui, pose sa copie avant de la décoder : l'origine le retient, et cette copie ne sera jamais réécrite (C-40).
     */
    private fun initData() {
        sweepOrphanTemps()
        if (path.exists()) {
            _data = format.decodeFile(dataSerializer, path)
            dataOrigin = DataOrigin.FILE
            hasSavedAtLeastOnce = true
        } else {
            _data = defaultDataProvider.invoke()
            dataOrigin = if (path.exists()) DataOrigin.RESOURCE else DataOrigin.DEFAULT
        }
    }

    /** Balaye les temporaires orphelins d'un crash passé, ceux du fichier et de son sidecar, au seul motif propre (C-28, [AtomicFiles.sweepOrphanTemps]). */
    private fun sweepOrphanTemps() = AtomicFiles.sweepOrphanTemps(path, metaPath)

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
            dataTreeProperties.add(prop)
            prop.annotations
                .filterIsInstance<StoreUpdatePolicy>()
                .firstOrNull()
                ?.let { ann -> updatePolicies[prop] = ann.policy }

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
     * Le message nomme l'origine des données, qui dit où est le remède : le fichier, la copie de la ressource, ou le code (C-40).
     * @throws ValidationException si les données sont invalides.
     */
    private fun initValidation() {
        if (!config.withValidation) return
        val result = runValidation(_data)
        if (result is ValidationResult.Failure) {
            val errors = if (path.exists()) ValidationErrorEnricher.enrich(format, path, result.errors) else result.errors
            val source = when (dataOrigin) {
                DataOrigin.FILE -> "loaded from file"
                DataOrigin.RESOURCE -> "copied from the default resource"
                DataOrigin.DEFAULT -> "default data"
            }
            throw ValidationException(errors, "[Storify] Store '${path}' ($source) is invalid:\n${ValidationResult.Failure(errors).formatFull()}")
        }
    }

    /**
     * Écrit le fichier initial des données nées par défaut, la validation étant passée : des défauts invalides ne touchent jamais le disque (C-06).
     * Sauf `createIfMissing = false` : les défauts vivent en mémoire et rien n'est écrit (C-30). La copie d'une ressource embarquée n'est pas
     * concernée : elle est déjà le fichier initial, et reste la ressource à l'octet (C-40).
     */
    private fun persistInitialData() {
        if (dataOrigin == DataOrigin.DEFAULT && config.createIfMissing) writeInitialFile()
    }

    /** Écrit le fichier initial quand aucun fichier n'existait (premier lancement). */
    private fun writeInitialFile() = dataLock.read { atomicWrite(path) { temp -> format.encodeToPath(dataSerializer, _data, temp) } }

    /** Enregistre la tâche planifiée d'auto-save, hors lecture seule (C-30). Le marquage dirty, lui, vit dans le pipeline d'update : voir [markDirty]. */
    private fun initAutoSave() {
        if (!config.withAutoSave || config.readOnly) return

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

    /** Arme le filet anti-crash, la persistance à l'arrêt de la JVM tant que le store n'est pas fermé, si [isShutdownHookArmed] le veut (C-30). */
    private fun initShutdownHook() {
        if (isShutdownHookArmed) Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    // ── La lecture et la validation ──

    override fun reloadFromFile(validate: Boolean) {
        checkOpen()
        val incoming = format.decodeFile(dataSerializer, path)
        if (validate && config.withValidation) {
            val result = runValidation(incoming)
            if (result is ValidationResult.Failure) {
                val errors = ValidationErrorEnricher.enrich(format, path, result.errors)
                throw ValidationException(errors, "[Storify] Reload of '$path' rejected, in-memory data untouched:\n${ValidationResult.Failure(errors).formatFull()}")
            }
        }
        replaceData(incoming)
    }

    /**
     * Remplace la racine sous write lock et notifie onReload : la voie interne de [reloadFromFile], le remplacement de racine n'est pas offert aux consommateurs (C-08).
     * Le fichier gagne (C-47) : une modification en mémoire pas encore sauvegardée est écartée, et dite au log en `warn` ; le drapeau dirty
     * retombe, la mémoire étant le fichier ; devant un auditeur de save, la référence de son prochain `old` devient la racine rechargée.
     * Les captures suivent [StoreConfig.useDeepCopy] (copies profondes, sinon les références) et ne se construisent que devant public (C-25, C-29).
     */
    internal fun replaceData(newValue: DATA) {
        val operation = dataLock.write {
            val old = _data
            _data = newValue
            if (isDirty) {
                log.warn("[Storify] Reload of '{}' discards unsaved in-memory changes: the file wins", path)
                isDirty = false
            }
            val copies = config.useDeepCopy
            val savedReference = copies && onSaveCallbacks.isNotEmpty()
            val newCopy = if (copies && (savedReference || onReloadCallbacks.isNotEmpty())) copyRoot(newValue) else null
            if (savedReference) lastSavedData = newCopy
            when {
                onReloadCallbacks.isEmpty() -> null
                copies -> ReloadOperation(this::data, CapturedValue.DeepCopy(copyRoot(old)), CapturedValue.DeepCopy(newCopy!!))
                else -> ReloadOperation(this::data, CapturedValue.Shallow(old), CapturedValue.Shallow(newValue))
            }
        }
        if (operation != null) onReloadCallbacks.forEach { it(operation) }
    }

    override fun validateNow(): ValidationResult = dataLock.read { runValidation(_data) }

    override fun validateFile(): ValidationResult {
        val onDisk = format.decodeFile(dataSerializer, path)
        return validator?.let { ValidationErrorEnricher.validate(format, path, onDisk, it) } ?: ValidationResult.Success
    }

    // ── Les mises à jour ──

    /** Le point d'entrée de `set` et `setIn` (C-45) : l'extension inline y arrive avec le sérialiseur de la valeur, matérialisé à son site réifié. */
    @PublishedApi
    internal fun <RECEIVER : Any, VALUE> setValue(property: KMutableProperty1<RECEIVER, VALUE>, newValue: VALUE, valueSerializer: () -> KSerializer<VALUE>, getReceiver: DATA.() -> RECEIVER) =
        runUpdate(property, inPlace = false, valueSerializer, getReceiver, applyUpdate = { receiver -> property.set(receiver, newValue) }, createOperation = { old, new -> SetOperation(property, old, new) })

    /** Le point d'entrée de `mutate` et `mutateIn` (C-45), sur le même principe. */
    @PublishedApi
    internal fun <RECEIVER : Any, VALUE : Any> mutateValue(property: KProperty1<RECEIVER, VALUE>, valueSerializer: () -> KSerializer<VALUE>, getReceiver: DATA.() -> RECEIVER, block: (VALUE) -> Unit) =
        runUpdate(property, inPlace = true, valueSerializer, getReceiver, applyUpdate = { receiver -> block(property.get(receiver)) }, createOperation = { old, new -> MutateOperation(property, old, new) })

    /** Le corps de `transaction` : la racine entière modifiée d'un bloc, tout ou rien, sous le write lock. */
    internal fun runTransaction(block: DATA.() -> Unit) {
        checkWritable()
        val operation: Operation<DATA> = dataLock.write {
            val backupSnapshot: DATA? = if (config.useDeepCopy) copyRoot(_data) else null

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

                if (onUpdateCallbacks.isEmpty()) {
                    TransactionOperation(CapturedValue.Unavailable, CapturedValue.Unavailable) // C-25 : pas de copie d'après sans public (le secours du rollback, lui, a déjà été pris)
                } else if (config.useDeepCopy && backupSnapshot != null) {
                    val o = CapturedValue.DeepCopy(backupSnapshot)
                    val n = CapturedValue.DeepCopy(copyRoot(_data))
                    TransactionOperation(o, n)
                } else TransactionOperation(CapturedValue.Unavailable, CapturedValue.Shallow(_data))

            } catch (e: Exception) {
                log.warn("[Storify] Transaction failed with exception, rolled back: {}", e.message)
                if (backupSnapshot != null) _data = backupSnapshot
                throw e
            }
        }
        onUpdateCallbacks.forEach { it(operation) }
    }

    /**
     * Le pipeline central d'update : la mutation sous le write lock, puis le dispatch des callbacks hors du lock. Une fonction ordinaire
     * (C-45) : les extensions `set`, `setIn`, `mutate` et `mutateIn` ne compilent chez l'appelant que la matérialisation du sérialiseur de
     * la valeur, que [valueSerializer] n'appelle que lorsqu'une copie est due.
     *
     * 1. La policy effective de la propriété est consultée : annotation, réglage runtime, puis défaut de config.
     * 2. `SKIP`, ou aucun auditeur (ni global ni ciblé sur la propriété, C-25) : la mutation s'applique,
     *    le dirty se pose, et rien n'est construit : aucune capture, aucune opération.
     * 3. Sinon : l'avant est capturé selon la policy, la mutation s'applique, l'après est capturé, l'opération naît. En SNAPSHOT, une
     *    valeur mutable est copiée en profondeur, une valeur immuable ([isImmutable]) arrive telle quelle. Sans copie, un `set` montre
     *    l'ancienne valeur, qu'il a remplacée ; une mutation en place ([inPlace]) n'a pas d'avant à montrer.
     * 4. Sous l'opt-in `validateOnUpdate`, la racine est validée après mutation, quelle que soit la policy :
     *    un échec restaure la copie de sécurité et dispatche une opération d'échec à la place.
     */
    private fun <RECEIVER : Any, VALUE> runUpdate(
        property: KProperty1<RECEIVER, VALUE>,
        inPlace: Boolean,
        valueSerializer: () -> KSerializer<VALUE>,
        getReceiver: DATA.() -> RECEIVER,
        applyUpdate: (RECEIVER) -> Unit,
        createOperation: (old: CapturedValue<VALUE>, new: CapturedValue<VALUE>) -> Operation<DATA>,
    ) {
        val outcome: UpdateOutcome<DATA>? = dataLock.write {
            checkWritable()

            val receiver = _data.getReceiver()

            val policy = updatePolicies[property] ?: config.defaultUpdatePolicy
            val observed = policy != UpdatePolicy.SKIP && hasUpdateListeners(property)
            val snapshots = observed && policy == UpdatePolicy.SNAPSHOT && config.useDeepCopy

            // C-05, opt-in validateOnUpdate : copie de sécurité de la racine (seul rollback générique d'une
            // mutation en place) et capture de l'avant, pour l'opération d'échec.
            val guardBackup: DATA? = if (config.validateOnUpdate) copyRoot(_data) else null

            val oldValue = property.get(receiver)
            val oldFrozen: CapturedValue<VALUE>? = if (snapshots || guardBackup != null) frozen(oldValue, valueSerializer) else null

            applyUpdate(receiver)

            if (guardBackup != null) {
                val failure = runValidation(_data) as? ValidationResult.Failure
                if (failure != null) {
                    val attempted = frozen(property.get(receiver), valueSerializer)
                    _data = guardBackup
                    return@write UpdateOutcome(ValidationFailedOperation(property, attempted, oldFrozen ?: CapturedValue.Unavailable, failure.formatFull()), property, receiver)
                }
            }
            markDirty()
            if (!observed) return@write null

            val newValue = property.get(receiver)
            val oldCaptured: CapturedValue<VALUE> = when {
                snapshots && oldFrozen != null -> oldFrozen
                inPlace -> CapturedValue.Unavailable // une mutation en place sans copie n'a pas d'avant à montrer
                else -> CapturedValue.Shallow(oldValue) // un set remplace la valeur : l'ancienne est l'avant
            }
            val newCaptured: CapturedValue<VALUE> = if (snapshots) frozen(newValue, valueSerializer) else CapturedValue.Shallow(newValue)

            UpdateOutcome(createOperation(oldCaptured, newCaptured), property, receiver)
        }
        if (outcome != null) dispatchUpdateCallbacks(outcome)
    }

    /**
     * Dispatche une [UpdateOutcome] aux callbacks enregistrés.
     * **Doit être appelé hors du [dataLock].**
     */
    private fun dispatchUpdateCallbacks(outcome: UpdateOutcome<DATA>) {
        onUpdateCallbacks.forEach { it(outcome.operation) }
        onUpdateCallbacksMap[outcome.prop]?.forEach { listener ->
            val navigate = listener.navigate
            if (navigate == null) {
                listener.callback(outcome.operation)
            } else {
                // Réévaluée à chaque notification sur les données du moment : l'écouteur survit aux
                // reloads, et une navigation qui échoue vaut « ne matche pas ».
                val target = runCatching { data.navigate() }.getOrNull()
                if (target === outcome.receiver) listener.callback(outcome.operation)
            }
        }
    }

    /** Vrai si au moins un callback d'update écoute cette propriété (le global compris) ; sinon, le pipeline court-circuite les captures (C-25). */
    private fun hasUpdateListeners(prop: KProperty1<*, *>): Boolean =
        onUpdateCallbacks.isNotEmpty() || onUpdateCallbacksMap[prop]?.isNotEmpty() == true

    /** La capture figée d'une valeur : une copie profonde par le copieur du format, ou la valeur elle-même quand elle est immuable. */
    private fun <VALUE> frozen(value: VALUE, valueSerializer: () -> KSerializer<VALUE>): CapturedValue<VALUE> =
        if (isImmutable(value)) CapturedValue.Shallow(value) else CapturedValue.DeepCopy(copier.copy(valueSerializer(), value))

    /**
     * Une valeur qu'une référence suffit à figer : `null`, une primitive, un `Char`, un `String` ou un enum. Elle n'est jamais copiée en
     * profondeur. Le jugement porte sur la valeur, pas sur le type déclaré (C-45) : un type réifié `Int` est vu sous sa forme boxée.
     */
    private fun isImmutable(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Char, is Enum<*> -> true
        is Byte, is Short, is Int, is Long, is Float, is Double -> true
        is UByte, is UShort, is UInt, is ULong -> true
        else -> false
    }

    /**
     * Marque les données modifiées : `meta.lastModified` et le drapeau dirty. Appelé par le pipeline d'update pour
     * TOUTES les policies, [UpdatePolicy.SKIP] compris : la persistance ne dépend pas de l'observation.
     */
    private fun markDirty() {
        if (config.withMeta) meta?.touch()
        isDirty = true
    }

    // ── Les callbacks ──

    override fun registerOnSave(callback: (Operation<DATA>) -> Unit) {
        onSaveCallbacks.add(callback)
        // La référence du prochain `old` se prend à l'arrivée du premier auditeur, tant que la mémoire est encore l'état du dernier save ;
        // un store en lecture seule ne sauve jamais, il n'a pas de référence à tenir (C-41).
        if (config.useDeepCopy && !config.readOnly && lastSavedData == null) dataLock.read {
            if (!isDirty && lastSavedData == null) lastSavedData = copyRoot(_data)
        }
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

    override fun registerOnUpdateOn(prop: KProperty1<DATA, *>, callback: (Operation<DATA>) -> Unit) {
        warnIfSilent(prop)
        onUpdateCallbacksMap.computeIfAbsent(prop) { CopyOnWriteArrayList() }.add(TargetedListener(null, callback))
    }

    override fun <R : Any> registerOnUpdateOnIn(prop: KProperty1<R, *>, receiver: DATA.() -> R, callback: (Operation<DATA>) -> Unit) {
        warnIfSilent(prop)
        onUpdateCallbacksMap.computeIfAbsent(prop) { CopyOnWriteArrayList() }.add(TargetedListener(receiver, callback))
    }

    /** Le garde-fou C-22 : un callback d'update enregistré sur une propriété à policy effective SKIP restera muet. */
    private fun warnIfSilent(prop: KProperty1<*, *>) {
        if (getUpdatePolicy(prop) == UpdatePolicy.SKIP) {
            log.warn("[Storify] Update callback registered on '{}' but its effective policy is SKIP: it will stay silent (annotate @StoreUpdatePolicy, set defaultUpdatePolicy, or call setUpdatePolicy)", prop.name)
        }
    }

    // ── Les policies ──

    /** Change la politique d'update d'une propriété au runtime ; une propriété hors de l'arbre de DATA est signalée, elle ne s'appliquera jamais. */
    fun setUpdatePolicy(prop: KProperty1<*, *>, policy: UpdatePolicy) {
        if (!belongsToDataTree(prop)) {
            log.warn("[Storify] Update policy set on '{}' but it does not belong to the data tree of '{}': it will never apply", prop.name, path)
        }
        updatePolicies[prop] = policy
    }

    /** Récupère la politique d'update d'une propriété. */
    fun getUpdatePolicy(prop: KProperty1<*, *>): UpdatePolicy = updatePolicies[prop] ?: config.defaultUpdatePolicy

    /** Vrai si la propriété appartient à l'arbre de [DATA], classes imbriquées comprises. Interne pour les tests. */
    internal fun belongsToDataTree(prop: KProperty1<*, *>): Boolean = prop in dataTreeProperties

    // ── La persistance ──

    override fun saveImmediate() = save(SaveTrigger.IMMEDIATE)

    /**
     * Persiste [_data] sur disque et écrit le sidecar meta. Devant un auditeur de save, met aussi à jour le snapshot [lastSavedData] et
     * déclenche les [onSaveCallbacks] **hors du lock** ; sans auditeur, ni copie ni opération (C-41).
     */
    private fun save(trigger: SaveTrigger) {
        if (config.readOnly) {
            if (trigger == SaveTrigger.IMMEDIATE) checkWritable() // lève : closed d'abord, read-only sinon
            return // ni tick ni hook en lecture seule, et un tel store n'est jamais dirty : rien à faire pour les autres déclencheurs
        }
        if (closed.get()) {
            when (trigger) {
                SaveTrigger.IMMEDIATE -> throw IllegalStateException("[Storify] Store '$path' is closed")
                SaveTrigger.AUTO_SAVE -> return // un tick en vol pendant la fermeture s'éteint sans bruit
                else -> {} // CLOSE et SHUTDOWN : les sauvegardes de fin de vie passent
            }
        }
        val operation: Operation<DATA>? = dataLock.read {
            val listening = onSaveCallbacks.isNotEmpty()

            val previous = lastSavedData
            val oldCaptured: CapturedValue<DATA> = when {
                previous == null -> CapturedValue.Unavailable
                !hasSavedAtLeastOnce -> CapturedValue.Initial(previous)
                else -> CapturedValue.DeepCopy(previous)
            }

            synchronized(saveIoLock) {
                atomicWrite(path) { temp -> encodeDataTo(temp) }
                if (config.withMeta) atomicWrite(metaPath) { temp -> metaFormat.encodeToPath(StoreMeta.serializer(), meta!!, temp) }
            }
            isDirty = false // après une écriture réussie seulement : un échec laisse le dirty au prochain tick
            hasSavedAtLeastOnce = true

            if (!listening) return@read null // personne n'écoute les saves : ni copie ni opération (C-41)

            val snapshot = if (config.useDeepCopy) copyRoot(_data) else null
            lastSavedData = snapshot
            val newCaptured: CapturedValue<DATA> = if (snapshot != null) CapturedValue.DeepCopy(snapshot) else CapturedValue.Unavailable

            SaveOperation(oldCaptured, newCaptured, trigger)
        }
        if (operation != null) onSaveCallbacks.forEach { it(operation) }
    }

    /** Encode les données vers [temp] ; un format préservant reçoit en plus le texte actuel de la cible, pour ne réécrire que ce qui change (C-26). */
    private fun encodeDataTo(temp: Path) {
        val preserving = format as? PreservingStoreFormat
        if (preserving != null) {
            val previousText = if (path.exists()) runCatching { path.readText() }.getOrNull() else null
            preserving.encodeToPathPreserving(dataSerializer, _data, temp, previousText)
        } else {
            format.encodeToPath(dataSerializer, _data, temp)
        }
    }

    /** Écrit par [AtomicFiles.write] (C-02, public, C-34) : temporaire voisin, flush, déplacement atomique, dossiers parents garantis pour tout format ; la cible est toujours une version entière, et le repli non atomique s'annonce sous le logger du store (C-37). */
    private fun atomicWrite(target: Path, encodeTo: (Path) -> Unit) = AtomicFiles.write(target, log, encodeTo)

    override fun pauseAutoSave() {
        if (closed.get() || config.readOnly) return
        autoSavePaused.set(true)
        log.info("[Storify] Auto-save PAUSED")
    }

    override fun resumeAutoSave() {
        if (closed.get() || config.readOnly) return
        autoSavePaused.set(false)
        log.info("[Storify] Auto-save RESUMED")
    }

    override fun isAutoSavePaused(): Boolean = autoSavePaused.get()

    // ── La fin de vie ──

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
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Sans hook armé, la sauvegarde d'adieu est toujours due ; avec, seulement si le hook a pu être désarmé (sinon la JVM s'éteint déjà :
        // le hook fait ou fera la sauvegarde, inutile de doubler).
        val farewellDue = !isShutdownHookArmed || try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            false
        }

        if (farewellDue && isDirty) save(SaveTrigger.CLOSE)
        log.info("[Storify] Store '{}' closed.", path)
    }

    /** Le corps du hook, testable sans éteindre la JVM : annule le tick en vol et, comme la sauvegarde d'adieu de [close], ne sauve que dirty (C-23). */
    internal fun runShutdownHook() {
        autoSaveFuture?.cancel(false)
        if (isDirty) save(SaveTrigger.SHUTDOWN)
    }

    // ── Les aides communes ── appelées de partout, sous leurs appelants

    /** Refuse tout accès à un store fermé. */
    private fun checkOpen() {
        check(!closed.get()) { "[Storify] Store '$path' is closed" }
    }

    /** Refuse toute écriture sur un store fermé ou en lecture seule (C-30). */
    private fun checkWritable() {
        checkOpen()
        check(!config.readOnly) { "[Storify] Store '$path' is read-only" }
    }

    /** Copie la racine par le copieur du format. */
    private fun copyRoot(value: DATA): DATA = copier.copy(dataSerializer, value)

    /** Exécute le [validator] sur [data] et retourne un [ValidationResult]. */
    private fun runValidation(data: DATA): ValidationResult = validator?.evaluate(data) ?: ValidationResult.Success

    // ── Les types imbriqués ──

    /**
     * D'où viennent les données à l'ouverture : le fichier déjà présent (`FILE`), la ressource embarquée que [defaultDataProvider] vient de
     * copier à sa place (`RESOURCE`), ou les défauts du code, sans fichier (`DEFAULT`).
     */
    private enum class DataOrigin { FILE, RESOURCE, DEFAULT }

    /**
     * Le résultat du pipeline d'update ([runUpdate]) : l'opération capturée (snapshots old/new) et sa cible,
     * prêtes à être dispatchées aux callbacks **hors du lock**.
     */
    private class UpdateOutcome<DATA : Any>(
        val operation: Operation<DATA>,
        val prop: KProperty1<*, *>,
        val receiver: Any
    )

    /**
     * Un écouteur ciblé : sans navigation il écoute sa propriété où que l'update soit émis,
     * avec navigation il n'écoute que l'instance qu'elle désigne (comparaison par identité).
     */
    private class TargetedListener<DATA : Any>(
        val navigate: (DATA.() -> Any)?,
        val callback: (Operation<DATA>) -> Unit,
    )
}
