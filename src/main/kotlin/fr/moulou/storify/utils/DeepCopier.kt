// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Le copieur profond d'un store (C-29) : une copie indépendante de n'importe quelle valeur `@Serializable`, listes et maps comprises, par un
 * aller-retour de sérialisation. Le véhicule fourni est l'arbre JSON de kotlinx ([JsonTreeCopier]) : ni texte ni octets produits, et les
 * sérialiseurs écrits pour le JSON (un `decoder as JsonDecoder`, un `JsonTransformingSerializer`) y trouvent le décodeur qu'ils attendent.
 * Chaque [fr.moulou.storify.StoreFormat] désigne le sien par `deepCopier()`.
 */
interface DeepCopier {

    companion object {
        /** Le copieur par défaut : l'arbre JSON, au module de sérialiseurs par défaut de kotlinx. */
        val Default: DeepCopier = JsonTreeCopier(Json)
    }

    /** Rend une copie de [value] indépendante de l'original. */
    fun <T> copy(serializer: KSerializer<T>, value: T): T
}

/**
 * L'aller-retour par arbre `JsonElement` (`encodeToJsonElement` puis `decodeFromJsonElement`), sur un `Json` dérivé de [json] : son module de
 * sérialiseurs et ses réglages, avec `encodeDefaults`, `allowSpecialFloatingPointValues` et `allowStructuredMapKeys` forcés, pour qu'une copie
 * n'échoue jamais sur un défaut omis, un `NaN` ou une clé de map structurée.
 */
class JsonTreeCopier(json: Json) : DeepCopier {

    private val json: Json = Json(from = json) {
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
    }

    override fun <T> copy(serializer: KSerializer<T>, value: T): T = json.decodeFromJsonElement(serializer, json.encodeToJsonElement(serializer, value))
}

/** Sucre réifié : le sérialiseur est matérialisé au site d'appel, la copie passe par [copier] (l'arbre JSON par défaut). */
inline fun <reified T> T.deepCopy(copier: DeepCopier = DeepCopier.Default): T = copier.copy(serializer<T>(), this)
