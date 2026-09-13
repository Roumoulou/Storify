@file:Suppress("unused")

package fr.moulou.storify

import fr.moulou.storify.validation.Validator
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StorePath(val path: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreDefaultResource(val resourcePath: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreConfiguration(
    val withValidation: Boolean = true,
    val withAutoSave: Boolean = true,
    val withMeta: Boolean = false,
    val useDeepCopy: Boolean = true,
    val autoSaveIntervalMs: Long = 300_000L,
    val defaultUpdatePolicy: UpdatePolicy = UpdatePolicy.SKIP
)

enum class StoreFileFormatType { JSON, TOML }

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreFileFormat(val type: StoreFileFormatType = StoreFileFormatType.JSON)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreValidator(val validatorClass: KClass<out Validator<*>>)

/**
 * Politique d'update par propriété.
 *
 * | Policy         | Deep copy snapshot | Callbacks |
 * |----------------|--------------------|-----------|
 * | **SNAPSHOT**   | ✅                 | ✅        |
 * | **SHALLOW**    | ❌                 | ✅        |
 * | **SKIP**       | ❌                 | ❌        |
 */
enum class UpdatePolicy {
    SNAPSHOT,
    SHALLOW,
    SKIP
}

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreUpdatePolicy(val policy: UpdatePolicy)
