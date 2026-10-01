// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

@file:Suppress("unused")

package fr.moulou.storify.core

import fr.moulou.storify.*
import fr.moulou.storify.utils.AtomicFiles
import fr.moulou.storify.utils.StoreFormats
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation

/**
 * La factory des stores. Les huit `create*` sont `inline` pour une seule raison : matérialiser `DATA::class` et `serializer<DATA>()` à
 * leur site réifié (C-09). Tout le reste, la résolution des annotations, le choix du format, la config et le fournisseur des données
 * initiales, la construction du store, vit ici en fonctions ordinaires, derrière quatre points d'entrée, un par source de données
 * initiales (C-46) : le code d'un appelant, donc le jar d'un mod, ne contient que cet appel.
 */
object StoreFactory {

    /**
     * Crée un store entièrement configuré via les annotations de DATA.
     * Requiert `@StorePath`. Le companion object de DATA doit implémenter [Defaultable]<DATA>.
     */
    inline fun <reified DATA : Any> create(): BaseStore<DATA> = openFromCompanion(DATA::class, serializer<DATA>(), null, null, null, null)

    /**
     * Crée un store avec un path explicite.
     * Les annotations `@StoreConfiguration`, `@StoreFileFormat`, `@StoreValidator` sont toujours lues, et un paramètre explicite remplace en bloc
     * l'annotation qui lui correspond : une [config] passée ici efface `@StoreConfiguration` entière, ses champs non donnés valant les défauts de
     * [StoreConfig], pas ceux de l'annotation.
     *
     * Le companion object de DATA doit implémenter [Defaultable]<DATA>.
     */
    inline fun <reified DATA : Any> create(stringPath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> =
        openFromCompanion(DATA::class, serializer<DATA>(), stringPath, format, config, validator)

    /**
     * Crée un store en utilisant le constructeur sans argument de DATA.
     * Requiert `@StorePath`.
     */
    inline fun <reified DATA : Any> createFromConstructor(): BaseStore<DATA> = openFromConstructor(DATA::class, serializer<DATA>(), null, null, null, null)

    /**
     * Crée un store en utilisant le constructeur sans argument de DATA, avec path explicite.
     */
    inline fun <reified DATA : Any> createFromConstructor(stringPath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> =
        openFromConstructor(DATA::class, serializer<DATA>(), stringPath, format, config, validator)

    /**
     * Crée un store via une classe [Defaultable] externe.
     * Requiert `@StorePath`. D est instanciée via constructeur sans argument.
     */
    inline fun <reified DATA : Any, reified D : Defaultable<DATA>> createFromDefaultable(): BaseStore<DATA> =
        openFromDefaultable(DATA::class, serializer<DATA>(), D::class, null, null, null, null)

    /**
     * Crée un store via une classe [Defaultable] externe, avec path explicite.
     */
    inline fun <reified DATA : Any, reified D : Defaultable<DATA>> createFromDefaultable(stringPath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> =
        openFromDefaultable(DATA::class, serializer<DATA>(), D::class, stringPath, format, config, validator)

    /**
     * Crée un store depuis une ressource.
     * Requiert `@StorePath` et `@StoreDefaultResource`.
     * Au premier lancement, copie la ressource telle quelle vers le path cible, puis la décode : le fichier que l'admin trouve est
     * celui que le mod a livré, commentaires compris (C-40).
     */
    inline fun <reified DATA : Any> createFromResource(): BaseStore<DATA> = openFromResource(DATA::class, serializer<DATA>(), null, null, null, null, null)

    /**
     * Crée un store depuis une ressource, avec path et resourcePath explicites.
     */
    inline fun <reified DATA : Any> createFromResource(stringPath: String, resourcePath: String, format: StoreFormat? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> =
        openFromResource(DATA::class, serializer<DATA>(), stringPath, resourcePath, format, config, validator)

    /** Le point d'entrée de `create` : les données initiales viennent du companion `Defaultable` de DATA, réclamé quand le fichier manque. */
    @PublishedApi
    internal fun <DATA : Any> openFromCompanion(dataClass: KClass<DATA>, serializer: KSerializer<DATA>, stringPath: String?, format: StoreFormat?, config: StoreConfig?, validator: Validator<DATA>?): BaseStore<DATA> =
        open(dataClass, serializer, stringPath, format, config, validator) {
            {
                val companion = dataClass.companionObjectInstance ?: throw IllegalArgumentException("${dataClass.simpleName} must have a companion object")
                @Suppress("UNCHECKED_CAST")
                if (companion is Defaultable<*>) companion.getDefault() as DATA
                else throw IllegalArgumentException("${dataClass.simpleName} companion object must implement Defaultable<${dataClass.simpleName}>")
            }
        }

    /** Le point d'entrée de `createFromConstructor` : les données initiales viennent du constructeur sans argument de DATA. */
    @PublishedApi
    internal fun <DATA : Any> openFromConstructor(dataClass: KClass<DATA>, serializer: KSerializer<DATA>, stringPath: String?, format: StoreFormat?, config: StoreConfig?, validator: Validator<DATA>?): BaseStore<DATA> =
        open(dataClass, serializer, stringPath, format, config, validator) { { dataClass.createInstance() } }

    /** Le point d'entrée de `createFromDefaultable` : les données initiales viennent d'une classe [Defaultable] externe, instanciée par constructeur sans argument. */
    @PublishedApi
    internal fun <DATA : Any> openFromDefaultable(dataClass: KClass<DATA>, serializer: KSerializer<DATA>, defaultableClass: KClass<out Defaultable<DATA>>, stringPath: String?, format: StoreFormat?, config: StoreConfig?, validator: Validator<DATA>?): BaseStore<DATA> =
        open(dataClass, serializer, stringPath, format, config, validator) { { defaultableClass.createInstance().getDefault() } }

    /**
     * Le point d'entrée de `createFromResource` : au premier lancement, la ressource du classpath ([resourcePath], sinon celle de
     * `@StoreDefaultResource`) est copiée telle quelle vers le fichier du store, puis décodée (C-40). Une annotation absente est refusée net.
     */
    @PublishedApi
    internal fun <DATA : Any> openFromResource(dataClass: KClass<DATA>, serializer: KSerializer<DATA>, stringPath: String?, resourcePath: String?, format: StoreFormat?, config: StoreConfig?, validator: Validator<DATA>?): BaseStore<DATA> =
        open(dataClass, serializer, stringPath, format, config, validator) { resolution ->
            val resource = resourcePath ?: resolution.annotations.defaultResourcePath ?: throw IllegalArgumentException("${dataClass.simpleName} must be annotated with @StoreDefaultResource")
            val storePath = Paths.get(resolution.path)
            val loggerName = resolution.config.loggerName
            ({ copyResource(dataClass.java.classLoader, serializer, storePath, resource, resolution.format, loggerName) })
        }

    /**
     * La copie d'une ressource embarquée vers le fichier du store, à l'octet et par l'écrivain atomique (jamais de fichier tronqué, le flux
     * fermé, le repli non atomique annoncé sous le logger du store), puis son décodage depuis cette copie (C-40). Une ressource introuvable
     * est refusée avant toute écriture. Interne pour les tests.
     */
    internal fun <DATA : Any> copyResource(classLoader: ClassLoader, deserializer: DeserializationStrategy<DATA>, storePath: Path, resourcePath: String, format: StoreFormat, loggerName: String): DATA {
        val resource = classLoader.getResourceAsStream(resourcePath) ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        resource.use { stream -> AtomicFiles.write(storePath, LoggerFactory.getLogger(loggerName)) { temp -> Files.copy(stream, temp) } }
        return format.decodeFile(deserializer, storePath)
    }

    /**
     * La construction d'un store, commune aux quatre points d'entrée :
     * 1. lire les annotations de DATA, une seule fois ;
     * 2. arrêter le chemin (explicite, sinon `@StorePath`), puis le format et la config, chaque objet entier : **explicite > annotation > repli**
     *    (le registre des formats par l'extension du chemin, `StoreConfig()`), jamais une fusion champ par champ ;
     * 3. obtenir de [providerFactory] le fournisseur des données initiales, appelée maintenant pour ses refus nets, le fournisseur lui-même
     *    n'étant réclamé par le store que si le fichier manque ;
     * 4. construire le [BaseStore], avec le sérialiseur matérialisé au site réifié de la fabrique publique (C-09).
     */
    private fun <DATA : Any> open(dataClass: KClass<DATA>, serializer: KSerializer<DATA>, stringPath: String?, format: StoreFormat?, config: StoreConfig?, validator: Validator<DATA>?, providerFactory: (Resolution<DATA>) -> () -> DATA): BaseStore<DATA> {
        val annotations = readAnnotations(dataClass)

        val finalPath = stringPath ?: annotations.path ?: throw IllegalArgumentException("${dataClass.simpleName} must be annotated with @StorePath")
        val resolution = Resolution(finalPath, format ?: annotations.format ?: StoreFormats.getFormatForStringPath(finalPath), config ?: annotations.config ?: StoreConfig(), annotations)

        val provider = providerFactory(resolution)

        return BaseStore(Paths.get(finalPath), resolution.format, resolution.config, serializer, defaultDataProvider = provider, validator = validator ?: annotations.validator)
    }

    /** Lit les cinq annotations de [dataClass] d'un coup ; le validator annoté est instancié par réflexion, le format annoté par son enum. */
    private fun <DATA : Any> readAnnotations(dataClass: KClass<DATA>): Annotations<DATA> {
        val path = dataClass.findAnnotation<StorePath>()?.path

        val format: StoreFormat? = dataClass.findAnnotation<StoreFileFormat>()?.let {
            when (it.type) {
                StoreFileFormatType.JSON -> JsonFormat()
                StoreFileFormatType.TOML -> TomlFormat()
                StoreFileFormatType.JSON5 -> Json5Format()
            }
        }

        val config = dataClass.findAnnotation<StoreConfiguration>()?.let {
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

        val validator: Validator<DATA>? = dataClass.findAnnotation<StoreValidator>()?.let {
            @Suppress("UNCHECKED_CAST")
            it.validatorClass.createInstance() as Validator<DATA>
        }

        val defaultResourcePath = dataClass.findAnnotation<StoreDefaultResource>()?.resourcePath

        return Annotations(path, format, config, validator, defaultResourcePath)
    }

    /** Les cinq annotations de DATA, chaque champ `null` quand l'annotation est absente. */
    private class Annotations<DATA : Any>(val path: String?, val format: StoreFormat?, val config: StoreConfig?, val validator: Validator<DATA>?, val defaultResourcePath: String?)

    /** Ce qui est arrêté avant de construire le store : le chemin, le format et la config résolus, et les annotations lues. */
    private class Resolution<DATA : Any>(val path: String, val format: StoreFormat, val config: StoreConfig, val annotations: Annotations<DATA>)
}
