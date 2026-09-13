@file:Suppress("unused")

package fr.moulou.storify

/**
 * Valeur capturée (snapshot) au moment d'un update ou d'un save.
 *
 * Utilisée pour les champs `old` et `new` des [Operation].
 * Les callbacks reçoivent des [CapturedValue] qu'ils peuvent lire en toute sécurité
 * **hors du lock** de données.
 *
 * - [DeepCopy] : copie profonde, toujours fiable. **Ne pas muter.**
 * - [Shallow] : lecture shallow au moment de la capture. Fiable pour les types immutables (String, Int…).
 * - [Initial] : première donnée jamais enregistrée (premier save).
 * - [Unavailable] : aucune capture (ex: `useDeepCopy=false` + même référence avant/après).
 */
sealed class CapturedValue<out T> {

    /** Copie profonde — toujours fiable, immutable. */
    data class DeepCopy<out T>(val value: T) : CapturedValue<T>()

    /** Lecture shallow au moment de la capture — fiable pour types immutables uniquement. */
    data class Shallow<out T>(val value: T) : CapturedValue<T>()

    /** Première donnée jamais enregistrée (initial save). */
    data class Initial<out T>(val value: T) : CapturedValue<T>()

    /** Aucune capture possible. */
    data object Unavailable : CapturedValue<Nothing>()

    /** Extrait la valeur ou `null` si [Unavailable]. */
    val valueOrNull: @UnsafeVariance T?
        get() = when (this) {
            is DeepCopy  -> value
            is Shallow   -> value
            is Initial   -> value
            Unavailable  -> null
        }

    /** `true` si une valeur est disponible. */
    val isAvailable: Boolean get() = this !is Unavailable
}
