package fr.moulou.storify

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import li.songe.json5.Json5
import li.songe.json5.Json5EncoderConfig
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le format JSON5 (C-21) : le JSON des configs éditées à la main, commentaires, clés sans
 * guillemets, virgules traînantes et apostrophes compris.
 *
 * Le montage suit la conception de la brique `li.songe:json5` elle-même : le texte est du JSON5 de
 * bout en bout (parsé et écrit par [Json5]), et le [Json] de kotlinx ne sert que de moteur d'arbre
 * (data class vers `JsonElement` et retour), sans jamais produire de texte. L'API de la brique
 * étant entièrement texte, le fichier se lit entier : le créneau est la config, pas la donnée de
 * masse.
 *
 * Limite assumée : une sauvegarde réécrit le fichier depuis les données, les commentaires du
 * fichier ne lui survivent pas. La sauvegarde préservante est le chantier C-26.
 */
class Json5Format(
    private val json: Json = Json { encodeDefaults = true },
    private val encoderConfig: Json5EncoderConfig = Json5EncoderConfig(indent = "    ")
) : StoreFormat {

    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return json.decodeFromJsonElement(deserializer, Json5.parseToJsonElement(path.readText()))
    }

    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.writeText(Json5.encodeToString(json.encodeToJsonElement(serializer, data), encoderConfig))
    }

    override fun fileExtension(): String = "json5"

    fun underlyingJson(): Json = json
}
