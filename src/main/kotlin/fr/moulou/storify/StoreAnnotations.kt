// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

@file:Suppress("unused")

package fr.moulou.storify

import fr.moulou.storify.validation.Validator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
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
    val defaultUpdatePolicy: UpdatePolicy = UpdatePolicy.SKIP,
    val validateOnUpdate: Boolean = false,
    val readOnly: Boolean = false,
    val withShutdownHook: Boolean = true,
    val createIfMissing: Boolean = true,
    val loggerName: String = "Storify",
    val commentPolicy: CommentPolicy = CommentPolicy.FILE_WINS
)

enum class StoreFileFormatType { JSON, TOML, JSON5 }

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

/**
 * Qui gagne, du commentaire du code ([StoreComment]) ou de celui du fichier, à la sauvegarde d'un format qui écrit des commentaires (JSON5, C-64).
 * Dans les deux cas, un fichier qui naît reçoit les commentaires du code.
 */
enum class CommentPolicy {
    /** Le fichier gagne : une clé que le fichier porte déjà garde ce qu'elle a, commentée ou nue ; seule une clé qui s'ajoute reçoit le commentaire du code. */
    FILE_WINS,

    /** Le code gagne : à chaque sauvegarde, les commentaires de tête d'une clé commentée dans le code sont remplacés par les siens ; le reste du fichier reste à l'admin. */
    CODE_WINS
}

/**
 * Le commentaire que le fichier porte au-dessus de la clé, une ligne par élément ; sur la classe racine, en tête du fichier (C-64). Lu dans le
 * descripteur du sérialiseur, donc porté par les sérialiseurs que le plugin génère ; une liste, une map ou un type polymorphe ne se descendent pas,
 * la propriété qui les porte garde le sien. JSON5 l'écrit (`//`) ; JSON l'ignore, le JSON strict n'ayant pas de commentaire ; TOML l'ignore et écrit
 * `@TomlComment` de tomlkt. Une ligne qui porte un retour à la ligne s'écrit en autant de lignes. Qui gagne entre ce commentaire et celui du fichier :
 * [CommentPolicy].
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StoreComment(vararg val lines: String)
