// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5, et les déclarations de premier niveau passées de public à internal. Le reste du fichier est inchangé.

@file:Suppress("unused")

package fr.moulou.storify.internal.json5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.serializer

internal inline fun <reified T> Json.encodeToJson5String(
    value: T,
    config: Json5EncoderConfig = Json5.encoderConfig,
): String {
    return Json5.encodeToString(
        encodeToJsonElement(serializersModule.serializer(), value),
        config
    )
}

internal inline fun <reified T> Json.decodeFromJson5String(value: CharSequence): T {
    return decodeFromJsonElement<T>(Json5.parseToJsonElement(value))
}
