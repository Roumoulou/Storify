@file:Suppress("unused")

package fr.moulou.storify

import fr.moulou.storify.utils.DateUtils.formatLocal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Le sidecar de métadonnées (`<fichier>.meta.json`, toujours JSON) : les dates de vie du store,
 * une version de schéma en réserve, et un sac libre pour le consommateur.
 */
@Serializable
data class StoreMeta(
    @SerialName("createdAt")
    val createdAt: String = Clock.System.now().formatLocal(),

    /** Entretenu par le store à chaque update, via [touch] (C-13). */
    @SerialName("lastModified")
    var lastModified: String = Clock.System.now().formatLocal(),

    /** Réservée au versionnage de schéma (chantier C-17) : posée à 1, jamais incrémentée à ce jour. */
    @SerialName("version")
    var version: Int = 1,

    /** Le sac libre du consommateur (le banc l'affiche en jeu) ; la lib n'y écrit jamais. */
    @SerialName("custom")
    var customData: MutableMap<String, String> = mutableMapOf()
) {
    fun touch() {
        lastModified = Clock.System.now().formatLocal()
    }

    fun setCustom(key: String, value: String) {
        customData[key] = value
    }

    fun getCustom(key: String): String? = customData[key]
}