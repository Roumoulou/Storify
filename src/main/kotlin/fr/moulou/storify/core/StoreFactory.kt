// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

@file:Suppress("unused")

package fr.moulou.storify.core

import fr.moulou.storify.*
import fr.moulou.storify.utils.AtomicFiles
import fr.moulou.storify.utils.StoreFormats
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation

object StoreFactory {

    /**
     * Fournisseur de données par défaut pour un store.
     *
     * Encapsule la stratégie de création des données initiales (quand aucun fichier n'existe).
     * Les factories publiques du companion sont `inline reified` : le type générique est capturé au site
     * d'appel (dans les méthodes `create*()` de [StoreFactory], elles-mêmes `inline reified`).
     * Le [DefaultProvider] résultant est ensuite passé comme simple lambda à [createInternal].
     */
    fun interface DefaultProvider<DATA : Any> {
        fun provide(): DATA

        companion object {
            /** Via le companion object de DATA, qui doit implémenter [Defaultable]<DATA>. */
            inline fun <reified DATA : Any> fromCompanion(): DefaultProvider<DATA> = DefaultProvider {
                val companion = DATA::class.companionObjectInstance ?: throw IllegalArgumentException("${DATA::class.simpleName} must have a companion object")
                if (companion is Defaultable<*>) companion.getDefault() as DATA
                else throw IllegalArgumentException("${DATA::class.simpleName} companion object must implement Defaultable<${DATA::class.simpleName}>")
            }

            /** Via le constructeur sans argument de DATA. */
            inline fun <reified DATA : Any> fromConstructor(): DefaultProvider<DATA> = DefaultProvider { DATA::class.createInstance() }

            /** Via une classe [Defaultable] externe, instanciée par constructeur sans argument. */
            inline fun <reified DATA : Any, reified D : Defaultable<DATA>> fromDefaultable(): DefaultProvider<DATA> = DefaultProvider { D::class.createInstance().getDefault() }

            /**
             * Copie une ressource du classpath vers le fichier store, telle quelle, puis la décode (C-40).
             * Le [format] doit être le format **déjà résolu**, et [loggerName] le nom du logger du store (C-37). Le sérialiseur et le
             * chargeur de classes de DATA sont matérialisés ici, au site réifié (C-09) ; la copie vit hors de l'inline, dans [resourceProvider].
             */
            inline fun <reified DATA : Any> fromResource(storePath: Path, resourcePath: String, format: StoreFormat, loggerName: String = "Storify"): DefaultProvider<DATA> =
                resourceProvider(DATA::class.java.classLoader, serializer<DATA>(), storePath, resourcePath, format, loggerName)

            /**
             * Le fournisseur d'une ressource embarquée (C-40) : la ressource est copiée à l'octet par l'écrivain atomique (jamais de fichier
             * tronqué, le flux fermé, le repli non atomique annoncé sous le logger du store), puis décodée depuis cette copie. Une ressource
             * introuvable est refusée avant toute écriture.
             */
            @PublishedApi
            internal fun <DATA : Any> resourceProvider(classLoader: ClassLoader, deserializer: DeserializationStrategy<DATA>, storePath: Path, resourcePath: String, format: StoreFormat, loggerName: String): DefaultProvider<DATA> = DefaultProvider {
                val resource = classLoader.getResourceAsStream(resourcePath) ?: throw IllegalArgumentException("Resource not found: $resourcePath")
                resource.use { stream -> AtomicFiles.write(storePath, LoggerFactory.getLogger(loggerName)) { temp -> Files.copy(stream, temp) } }
                format.decodeFile(deserializer, storePath)
            }
        }
    }

    /**
     * Contient toutes les valeurs résolues depuis les annotations de DATA.
     * Chaque champ est nullable : `null` = annotation absente.
     */
    @PublishedApi
    internal data class ResolvedAnnotations<DATA : Any>(val path: String?, val format: StoreFormat?, val config: StoreConfig?, val validator: Validator<DATA>?, val defaultResourcePath: String?)

    /** Résout toutes les annotations de DATA d'un coup. */
    @PublishedApi
    internal inline fun <reified DATA : Any> resolveAllAnnotations(): ResolvedAnnotations<DATA> {
        val path = DATA::class.findAnnotation<StorePath>()?.path

        val format: StoreFormat? = DATA::class.findAnnotation<StoreFileFormat>()?.let {
            when (it.type) {
                StoreFileFormatType.JSON -> JsonFormat()
                StoreFileFormatType.TOML -> TomlFormat()
                StoreFileFormatType.JSON5 -> Json5Format()
            }
        }

        val config = DATA::class.findAnnotation<StoreConfiguration>()?.let {
            StoreConfig(
                withValidation = it.withValidation,
                withAutoSave = it.withAutoSave,
                withMeta = it.withMeta,
                useDeepCopy = it.useDeepCopy,
                autoSaveIntervalMs = it.autoSaveIntervalMs,
                defaultUpdatePolicy = it.defaultUpdatePolicy,
                validateOnUpdate = it.validateOnUpdate,
                readOnly = it.readOnly,
                withShutdownHook = it.withShutdownHook,
                createIfMissing = it.createIfMissing,
                loggerName = it.loggerName
            )
        }

        val validator: Validator<DATA>? = DATA::class.findAnnotation<StoreValidator>()?.let {
            @Suppress("UNCHECKED_CAST")
            it.validatorClass.createInstance() as Validator<DATA>
        }

        val defaultResourcePath = DATA::class.findAnnotation<StoreDefaultResource>()?.resourcePath

        return ResolvedAnnotations(path, format, config, validator, defaultResourcePath)
    }

    /**
     * Méthode centrale de création d'un store.
     *
     * **Toutes** les méthodes `create*` publiques délèguent ici.
     * Elle se charge de :
     * 1. Résoudre les annotations de DATA (une seule fois)
     * 2. Déterminer le path final (explicite ou annoté)
     * 3. Fusionner les paramètres : **explicite > annotation > fallback**
     * 4. Matérialiser le sérialiseur de DATA à son site réifié : c'est lui que le store passera au
     *    [StoreFormat], polymorphe, format tiers compris (C-09)
     * 5. Obtenir le [DefaultProvider] via la [providerFactory] (qui peut avoir besoin du format et de la config résolus)
     * 6. Construire le [BaseStore]
     *
     * @param stringPath      Path explicite, ou `null` pour l'extraire de `@StorePath`
     * @param format          Format explicite (nullable ; priorité sur l'annotation)
     * @param config          Config explicite (nullable ; priorité sur l'annotation)
     * @param validator       Validator explicite (nullable ; priorité sur l'annotation)
     * @param providerFactory Factory qui reçoit le path final, les annotations résolues, le format résolu et la
     *                        config résolue, et retourne le [DefaultProvider] approprié.
     *                        Appelée **après** la résolution des paramètres, ce qui permet à
     *                        `fromResource` d'utiliser le format final et le nom du logger du store.
     */
    @PublishedApi
    internal inline fun <reified DATA : Any> createInternal(stringPath: String?, format: StoreFormat?, config: StoreConfig?, validator: Validator<DATA>?, providerFactory: (path: String, resolved: ResolvedAnnotations<DATA>, resolvedFormat: StoreFormat, resolvedConfig: StoreConfig) -> DefaultProvider<DATA>): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()

        val finalPath = stringPath ?: resolved.path ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StorePath")

        val finalFormat = format ?: resolved.format ?: StoreFormats.getFormatForStringPath(finalPath)
        val finalConfig = config ?: resolved.config ?: StoreConfig()
        val finalValidator = validator ?: resolved.validator

        val provider = providerFactory(finalPath, resolved, finalFormat, finalConfig)

        return BaseStore(
            Paths.get(finalPath), finalFormat, finalConfig,
            serializer<DATA>(),
            defaultDataProvider = { provider.provide() },
            validator = finalValidator
        )
    }

    /**
     * Crée un store entièrement configuré via les annotations de DATA.
     * Requiert `@StorePath`. Le companion object de DATA doit implémenter [Defaultable]<DATA>.
     */
    inline fun <reified DATA : Any> create(): BaseStore<DATA> = createInternal(null, null, null, null) { _, _, _, _ -> DefaultProvider.fromCompanion<DATA>() }

    /**
     * Crée un store avec un path explicite.
     * Les annotations `@StoreConfiguration`, `@StoreFileFormat`, `@StoreValidator` sont toujours lues.
     * Les paramètres explicites ont priorité sur les annotations.
     *
     * Le companion object de DATA doit implémenter [Defaultable]<DATA>.
     */
    inline fun <reified DATA : Any> create(stringPath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> = createInternal(stringPath, format, config, validator) { _, _, _, _ -> DefaultProvider.fromCompanion<DATA>() }

    /**
     * Crée un store en utilisant le constructeur sans argument de DATA.
     * Requiert `@StorePath`.
     */
    inline fun <reified DATA : Any> createFromConstructor(): BaseStore<DATA> = createInternal(null, null, null, null) { _, _, _, _ -> DefaultProvider.fromConstructor<DATA>() }

    /**
     * Crée un store en utilisant le constructeur sans argument de DATA, avec path explicite.
     */
    inline fun <reified DATA : Any> createFromConstructor(stringPath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> = createInternal(stringPath, format, config, validator) { _, _, _, _ -> DefaultProvider.fromConstructor<DATA>() }

    /**
     * Crée un store via une classe [Defaultable] externe.
     * Requiert `@StorePath`. D est instanciée via constructeur sans argument.
     */
    inline fun <reified DATA : Any, reified D : Defaultable<DATA>> createFromDefaultable(): BaseStore<DATA> = createInternal(null, null, null, null) { _, _, _, _ -> DefaultProvider.fromDefaultable<DATA, D>() }

    /**
     * Crée un store via une classe [Defaultable] externe, avec path explicite.
     */
    inline fun <reified DATA : Any, reified D : Defaultable<DATA>> createFromDefaultable(stringPath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> = createInternal(stringPath, format, config, validator) { _, _, _, _ -> DefaultProvider.fromDefaultable<DATA, D>() }

    /**
     * Crée un store depuis une ressource.
     * Requiert `@StorePath` et `@StoreDefaultResource`.
     * Au premier lancement, copie la ressource telle quelle vers le path cible, puis la décode : le fichier que l'admin trouve est
     * celui que le mod a livré, commentaires compris (C-40).
     */
    inline fun <reified DATA : Any> createFromResource(): BaseStore<DATA> = createInternal(null, null, null, null) { path, resolved, resolvedFormat, resolvedConfig ->
            val resourcePath = resolved.defaultResourcePath
                ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StoreDefaultResource")
            DefaultProvider.fromResource<DATA>(Paths.get(path), resourcePath, resolvedFormat, resolvedConfig.loggerName)
        }

    /**
     * Crée un store depuis une ressource, avec path et resourcePath explicites.
     */
    inline fun <reified DATA : Any> createFromResource(stringPath: String, resourcePath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> = createInternal(stringPath, format, config, validator) { path, _, resolvedFormat, resolvedConfig -> DefaultProvider.fromResource<DATA>(Paths.get(path), resourcePath, resolvedFormat, resolvedConfig.loggerName) }
}
