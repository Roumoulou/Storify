// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.core

import fr.moulou.storify.Operation
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.StoreMeta
import fr.moulou.storify.validation.ValidationResult
import java.nio.file.Path
import kotlin.reflect.KProperty1

interface Store<DATA : Any> : AutoCloseable {

    val data: DATA
    val path: Path
    val format: StoreFormat
    val meta: StoreMeta?

    fun saveImmediate()

    /**
     * Relit le fichier et remplace les données en mémoire, avec revalidation par défaut : en échec,
     * la mémoire reste intacte et une ValidationException remonte. `validate = false` saute la revalidation.
     */
    fun reloadFromFile(validate: Boolean = true)

    /**
     * Valide les données en mémoire avec le validator du store (Success sans validator), sans numéros de ligne : une ligne de fichier ne vaut
     * que si la mémoire et le fichier coïncident ; pour le fichier lui-même, voir [validateFile].
     */
    fun validateNow(): ValidationResult

    /**
     * Valide le fichier du store tel qu'il est sur le disque, sans toucher la mémoire (C-32) : décodé, validé, les erreurs enrichies des
     * lignes quand le format sait les localiser. Un fichier qui ne se décode pas lève, comme au rechargement.
     */
    fun validateFile(): ValidationResult

    /**
     * Enregistre un callback de save : après chaque sauvegarde, il reçoit une `SaveOperation` qui porte la racine telle qu'au save précédent
     * (`old`, `Initial` tant que le store n'a jamais sauvé) et telle qu'elle vient d'être écrite (`new`), deux copies profondes sous
     * `useDeepCopy`. Ces copies ne se prennent que devant un auditeur (C-41) : la première référence est prise ici, à l'enregistrement, si le
     * store n'a pas de modification en attente. Enregistré sur un store déjà modifié, le callback reçoit `Unavailable` en `old` à son
     * premier save : un callback de save s'enregistre à l'ouverture du store, avant toute modification.
     */
    fun registerOnSave(callback: (Operation<DATA>) -> Unit)
    fun registerOnReload(callback: (Operation<DATA>) -> Unit)
    fun registerOnUpdate(callback: (Operation<DATA>) -> Unit)

    /**
     * Enregistre un callback ciblé sur une propriété de la racine [DATA] : il n'est notifié que
     * des updates de cette propriété, où que l'update ait été émis. Le lien repose sur l'égalité
     * des références de propriété (`Data::champ` venu de deux sites d'appel désigne la même clé).
     * Une propriété imbriquée s'écoute par [registerOnUpdateOnIn] ; une classe imbriquée entière
     * s'observe par [registerOnUpdate], filtré sur `operation.prop`.
     */
    fun registerOnUpdateOn(prop: KProperty1<DATA, *>, callback: (Operation<DATA>) -> Unit)

    /**
     * Enregistre un callback ciblé sur une propriété imbriquée, pour la seule instance que
     * [receiver] désigne : le miroir de `setIn`/`mutateIn`. La navigation ancre la propriété à ce
     * store à la compilation (aucun chemin honnête ne mène à une classe étrangère), puis elle est
     * réévaluée à chaque notification sur les données du moment et comparée par identité au
     * receiver de l'update : l'écouteur survit donc aux rechargements, une navigation qui échoue
     * vaut simplement « ne matche pas », et seul l'exemplaire visé est notifié.
     */
    fun <R : Any> registerOnUpdateOnIn(prop: KProperty1<R, *>, receiver: DATA.() -> R, callback: (Operation<DATA>) -> Unit)

    fun pauseAutoSave()

    fun resumeAutoSave()

    fun isAutoSavePaused(): Boolean

    /** `true` pour un store en lecture seule (C-30) : il lit, valide et relit, et refuse toute écriture. */
    val isReadOnly: Boolean

    /** `true` après [close] : le store reste lisible, mais fermé aux écritures. */
    val isClosed: Boolean

    /** Détache proprement le store : tick annulé, planificateur arrêté, hook JVM désarmé, sauvegarde d'adieu si dirty. Idempotent. */
    override fun close()

}
