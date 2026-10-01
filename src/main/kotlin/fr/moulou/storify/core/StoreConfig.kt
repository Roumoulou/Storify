// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.core

import fr.moulou.storify.StoreUpdatePolicy
import fr.moulou.storify.UpdatePolicy
import fr.moulou.storify.ValidationFailedOperation

/**
 * Configuration d'une instance [BaseStore].
 *
 * @property withValidation    Active la validation au chargement initial (fichier ou données par défaut). Défaut `true` (C-24) : sans validator
 *                             elle ne coûte rien, et poser un validator c'est vouloir qu'il tourne ; `false` est l'échappatoire explicite.
 * @property withAutoSave      Persiste automatiquement les données modifiées sur disque. Défaut `true`.
 * @property withMeta          Gère un fichier sidecar `.meta.json` (lastModified, etc.). Défaut `false`.
 * @property useDeepCopy       Autorise les copies profondes, par le copieur du format (C-29) : les captures old/new des callbacks (policy
 *                             SNAPSHOT), le secours de rollback des transactions, le snapshot du dernier save et les captures du
 *                             rechargement. Les captures ne se prennent que devant un auditeur (C-25, C-41) ; seul le secours des
 *                             transactions se prend toujours. Défaut `true`. À `false`, plus aucune copie : ces captures sont
 *                             `Unavailable` ou `Shallow`, et la transaction perd son filet.
 * @property defaultUpdatePolicy Politique d'update par défaut pour les propriétés sans annotation
 *                               [StoreUpdatePolicy]. Défaut [UpdatePolicy.SKIP] : sans policy explicite, les callbacks
 *                               se taisent. La persistance (marquage dirty), elle, est garantie pour toutes les policies.
 * @property autoSaveIntervalMs  Intervalle en millisecondes entre chaque tick d'auto-save. Défaut 5 min.
 * @property validateOnUpdate  Valide la racine à CHAQUE update, avec rollback et [ValidationFailedOperation] en échec.
 *                             NON RECOMMANDÉ : copie de la racine entière et validator sous write lock à chaque geste ;
 *                             préférez des contrôles métier avant de muter. Exige [useDeepCopy]. Défaut `false`.
 * @property readOnly          Le store lit, valide et relit son fichier, et n'écrit jamais (C-30) : `set`, `mutate`, `transaction` et
 *                             `saveImmediate` lèvent [IllegalStateException], ni planificateur d'auto-save (quel que soit [withAutoSave]) ni
 *                             hook d'arrêt, `pauseAutoSave` et `resumeAutoSave` inertes, le sidecar meta lu mais jamais écrit. Sa seule
 *                             écriture possible est le fichier initial, si [createIfMissing]. Défaut `false`.
 * @property withShutdownHook  Arme le hook d'arrêt de la JVM, le filet anti-crash qui sauve un store encore dirty à l'extinction. `false`
 *                             pour un store que le consommateur ferme lui-même ; `close()` fait toujours sa sauvegarde d'adieu. Forcé à
 *                             `false` par [readOnly]. Défaut `true`.
 * @property createIfMissing   Écrit le fichier initial né des défauts quand il manque, la validation passée. À `false`, les défauts vivent en
 *                             mémoire et rien n'est écrit à l'ouverture (un `saveImmediate` ultérieur crée le fichier). Sans effet sur
 *                             `createFromResource`, qui copie toujours sa ressource, telle quelle (C-40) : cette copie est sa définition.
 *                             Défaut `true`.
 * @property loggerName        Le nom du logger SLF4J du store (C-37) : le nom du mod (`aegisperms`) range les lignes du store sous son journal,
 *                             préfixe `[Storify]` gardé. Défaut `Storify`.
 */
data class StoreConfig(
    val withValidation: Boolean = true,
    val withAutoSave: Boolean = true,
    val withMeta: Boolean = false,
    val useDeepCopy: Boolean = true,
    val defaultUpdatePolicy: UpdatePolicy = UpdatePolicy.SKIP,
    val autoSaveIntervalMs: Long = 300_000L,
    val validateOnUpdate: Boolean = false,
    val readOnly: Boolean = false,
    val withShutdownHook: Boolean = true,
    val createIfMissing: Boolean = true,
    val loggerName: String = "Storify"
)
