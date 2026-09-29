// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import fr.moulou.storify.utils.DeepCopier
import fr.moulou.storify.utils.JsonTreeCopier
import fr.moulou.storify.utils.withoutUtf8Bom
import fr.moulou.storify.validation.ErrorLineLocator
import fr.moulou.storify.validation.JsonLineLocator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Le format JSON : le JSON standard, strict à la lecture (C-36). Un commentaire, une clé ou une chaîne sans guillemets échouent au décodage
 * comme une virgule finale ou une clé inconnue, et le store lève [StoreDecodeException] avec la ligne ; le fichier édité à la main qui veut
 * ces libertés a son format, [Json5Format]. Le défaut garde `prettyPrint` et `encodeDefaults` (un fichier lisible qui porte tous ses
 * champs), `allowStructuredMapKeys` (une map à clés structurées s'écrit en tableau ; le réglage ne tolère aucune syntaxe) et
 * `allowSpecialFloatingPointValues` (un `NaN` s'écrit et se relit : le refuser ferait échouer chaque sauvegarde du store, loin du code qui
 * a produit la valeur).
 *
 * [lenient] rend le lecteur tolérant, et le constructeur accepte tout `Json` : un consommateur qui veut ses propres réglages le passe.
 */
class JsonFormat(
    private val json: Json = standardJson()
) : StoreFormat {

    companion object {
        /** Le `Json` du défaut ; avec [tolerant], celui du lecteur tolérant. */
        private fun standardJson(tolerant: Boolean = false): Json = Json {
            prettyPrint = true
            encodeDefaults = true
            allowStructuredMapKeys = true
            allowSpecialFloatingPointValues = true
            isLenient = tolerant
            allowComments = tolerant
        }

        /** Le lecteur tolérant : le défaut, plus les commentaires (`allowComments`) et les clés et chaînes sans guillemets (`isLenient`). */
        fun lenient(): JsonFormat = JsonFormat(standardJson(tolerant = true))
    }

    /** Le copieur profond des stores JSON : l'arbre JSON de ce même `Json` (C-29). */
    private val copier = JsonTreeCopier(json)

    override fun deepCopier(): DeepCopier = copier

    override fun lineLocator(): ErrorLineLocator = JsonLineLocator

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return path.inputStream().withoutUtf8Bom().use { stream -> json.decodeFromStream(deserializer, stream) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.outputStream().use { stream -> json.encodeToStream(serializer, data, stream) }
    }

    override fun fileExtension(): String = "json"

    fun underlyingJson(): Json = json
}
