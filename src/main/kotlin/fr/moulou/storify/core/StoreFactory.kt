@file:Suppress("unused")

package fr.moulou.storify.core

import fr.moulou.storify.*
import fr.moulou.storify.utils.Utils
import fr.moulou.storify.utils.deepCopyViaCbor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance
import fr.moulou.storify.validation.Validator
import kotlin.reflect.full.findAnnotation

object StoreFactory {

    inline fun <reified T> StoreFormat<*>.createEncoder(): (T, Path) -> Unit = { data, path ->
        when (this) {
            is JsonFormat -> this.encodeToPath(data, path)
            is TomlFormat -> this.encodeToPath(data, path)
            else -> throw IllegalArgumentException("Format unsupported: ${this::class.simpleName}")
        }
    }

    inline fun <reified T> StoreFormat<*>.createDecoder(): (Path) -> T = { path ->
        when (this) {
            is JsonFormat -> this.decodeFromPath(path)
            is TomlFormat -> this.decodeFromPath(path)
            else -> throw IllegalArgumentException("Format unsupported: ${this::class.simpleName}")
        }
    }

    @PublishedApi
    internal inline fun <reified DATA : Any> buildStore(stringPath: String, format: StoreFormat<*>, config: StoreConfig, validator: Validator<DATA>? = null, noinline defaultDataProvider: () -> DATA): BaseStore<DATA> {
        return BaseStore(
            Paths.get(stringPath), format, config,
            format.createDecoder(), format.createEncoder(),
            format.createDecoder<StoreMeta>(), format.createEncoder<StoreMeta>(),
            defaultDataProvider,
            deepCopyFn = { it.deepCopyViaCbor() },
            validator = validator
        )
    }

    /**
     * Contient toutes les valeurs résolues depuis les annotations de DATA.
     */
    @PublishedApi
    internal data class ResolvedAnnotations<DATA : Any>(
        val path: String?,
        val format: StoreFormat<*>?,
        val config: StoreConfig?,
        val validator: Validator<DATA>?,
        val defaultResourcePath: String?
    )

    /**
     * Résout Toutes les annotations d'un coup : path, format, config, validator, defaultResource.
     */
    @PublishedApi
    internal inline fun <reified DATA : Any> resolveAllAnnotations(): ResolvedAnnotations<DATA> {
        val path = DATA::class.findAnnotation<StorePath>()?.path

        val format: StoreFormat<*>? = DATA::class.findAnnotation<StoreFileFormat>()?.let {
            when (it.type) {
                StoreFileFormatType.JSON -> JsonFormat()
                StoreFileFormatType.TOML -> TomlFormat()
            }
        }

        val config = DATA::class.findAnnotation<StoreConfiguration>()?.let {
            StoreConfig(
                withValidation = it.withValidation,
                withAutoSave = it.withAutoSave,
                withMeta = it.withMeta,
                useDeepCopy = it.useDeepCopy,
                autoSaveIntervalMs = it.autoSaveIntervalMs
            )
        }

        val validator: Validator<DATA>? = DATA::class.findAnnotation<StoreValidator>()?.let {
            @Suppress("UNCHECKED_CAST")
            it.validatorClass.createInstance() as Validator<DATA>
        }

        val defaultResourcePath = DATA::class.findAnnotation<StoreDefaultResource>()?.resourcePath

        return ResolvedAnnotations(path, format, config, validator, defaultResourcePath)
    }

    @PublishedApi
    internal inline fun <reified DATA : Any> getDefaultFromCompanion(): DATA {
        val companion = DATA::class.companionObjectInstance ?: throw IllegalArgumentException("${DATA::class.simpleName} must have a companion object")
        if (companion is Defaultable<*>) {
            @Suppress("UNCHECKED_CAST")
            return (companion as Defaultable<DATA>).getDefault()
        } else {
            throw IllegalArgumentException("${DATA::class.simpleName} companion object must implement Defaultable<${DATA::class.simpleName}>")
        }
    }



    // ══════════════════════════════════════════════════════════════
    //  CREATE METHODS
    // ══════════════════════════════════════════════════════════════

    /**
     * Crée un store entièrement configuré via les annotations de DATA.
     * Requiert @StorePath sur DATA.
     */
    inline fun <reified DATA : Any> create(): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val path = resolved.path ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StorePath")
        val format = resolved.format ?: Utils.getFormatForStringPath(path)
        val config = resolved.config ?: StoreConfig()

        return create(path, format, config, resolved.validator)
    }

    /**
     * Crée un store avec un path explicite. Les annotations @StoreConfiguration, @StoreFileFormat, @StoreValidator sont toujours lues.
     * Les paramètres explicites (format, config, validator) ont priorité sur les annotations.
     */
    inline fun <reified DATA : Any> create(stringPath: String, format: StoreFormat<*>? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val resolvedFormat = format ?: resolved.format ?: Utils.getFormatForStringPath(stringPath)
        val resolvedConfig = config ?: resolved.config ?: StoreConfig()
        val resolvedValidator = validator ?: resolved.validator

        return buildStore(stringPath, resolvedFormat, resolvedConfig, resolvedValidator) { getDefaultFromCompanion() }
    }

    /**
     * Crée un store en utilisant le constructeur sans argument de DATA.
     * Requiert @StorePath sur DATA.
     */
    inline fun <reified DATA : Any> createFromConstructor(): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val path = resolved.path ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StorePath")
        val format = resolved.format ?: Utils.getFormatForStringPath(path)
        val config = resolved.config ?: StoreConfig()

        return createFromConstructor(path, format, config, resolved.validator)
    }

    /**
     * Crée un store en utilisant le constructeur sans argument de DATA, avec path explicite.
     */
    inline fun <reified DATA : Any> createFromConstructor(stringPath: String, format: StoreFormat<*>? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val resolvedFormat = format ?: resolved.format ?: Utils.getFormatForStringPath(stringPath)
        val resolvedConfig = config ?: resolved.config ?: StoreConfig()
        val resolvedValidator = validator ?: resolved.validator

        return buildStore(stringPath, resolvedFormat, resolvedConfig, resolvedValidator) { DATA::class.createInstance() }
    }

    /**
     * Crée un store en utilisant une classe Defaultable externe.
     * Requiert @StorePath sur DATA.
     */
    inline fun <reified DATA : Any, reified D : Defaultable<DATA>> createFromDefaultable(): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val path = resolved.path ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StorePath")
        val format = resolved.format ?: Utils.getFormatForStringPath(path)
        val config = resolved.config ?: StoreConfig()

        return createFromDefaultable(path, format, config, resolved.validator)
    }

    /**
     * Crée un store en utilisant une classe Defaultable externe, avec path explicite.
     */
    inline fun <reified DATA : Any, reified D : Defaultable<DATA>> createFromDefaultable(stringPath: String, format: StoreFormat<*>? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val resolvedFormat = format ?: resolved.format ?: Utils.getFormatForStringPath(stringPath)
        val resolvedConfig = config ?: resolved.config ?: StoreConfig()
        val resolvedValidator = validator ?: resolved.validator

        return buildStore(stringPath, resolvedFormat, resolvedConfig, resolvedValidator) { D::class.createInstance().getDefault() }
    }

    /**
     * Crée un store depuis une ressource. Requiert @StorePath et @StoreDefaultResource sur DATA.
     */
    inline fun <reified DATA : Any> createFromResource(): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val path = resolved.path ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StorePath")
        val resourcePath = resolved.defaultResourcePath ?: throw IllegalArgumentException("${DATA::class.simpleName} must be annotated with @StoreDefaultResource")
        val format = resolved.format ?: Utils.getFormatForStringPath(path)
        val config = resolved.config ?: StoreConfig()

        return createFromResource(path, resourcePath, format, config, resolved.validator)
    }

    inline fun <reified DATA : Any> createFromResource(stringPath: String, resourcePath: String, format: StoreFormat<*>? = null, config: StoreConfig? = null, validator: Validator<DATA>? = null): BaseStore<DATA> {
        val resolved = resolveAllAnnotations<DATA>()
        val resolvedFormat = format ?: resolved.format ?: Utils.getFormatForStringPath(stringPath)
        val resolvedConfig = config ?: resolved.config ?: StoreConfig()
        val resolvedValidator = validator ?: resolved.validator

        val path = Paths.get(stringPath)
        val decoder = resolvedFormat.createDecoder<DATA>()

        return buildStore(stringPath, resolvedFormat, resolvedConfig, resolvedValidator) {
            path.parent?.createDirectories()
            val inputStream = DATA::class.java.classLoader.getResourceAsStream(resourcePath) ?: throw IllegalArgumentException("Resource not found: $resourcePath")
            Files.copy(inputStream, path)
            decoder(path)
        }
    }

}