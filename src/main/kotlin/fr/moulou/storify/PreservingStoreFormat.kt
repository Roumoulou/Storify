// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import kotlinx.serialization.SerializationStrategy
import java.nio.file.Path

/**
 * La capacité optionnelle d'un format (C-26) : sauvegarder en préservant le texte existant
 * (commentaires, ordre, style d'écriture), en ne réécrivant que ce qui a changé. `BaseStore` la
 * détecte au moment du save et fournit le contenu actuel du fichier cible ; les formats ordinaires
 * n'ont rien à savoir de tout ceci, le contrat [StoreFormat] reste intact.
 */
interface PreservingStoreFormat : StoreFormat {

    /**
     * Encode [data] vers [path] en réconciliant avec [previousText], le contenu actuel du fichier
     * cible (`null` quand il n'existe pas) ; un texte invalide ou irréconciliable vaut encode à neuf.
     */
    fun <DATA> encodeToPathPreserving(serializer: SerializationStrategy<DATA>, data: DATA, path: Path, previousText: String?)

    /**
     * La même sauvegarde, avec la politique des commentaires du store (C-64) : qui gagne, du commentaire du code ([StoreComment]) ou de celui du
     * fichier. Un format qui n'écrit pas de commentaires garde ce défaut, qui ignore la politique.
     */
    fun <DATA> encodeToPathPreserving(serializer: SerializationStrategy<DATA>, data: DATA, path: Path, previousText: String?, commentPolicy: CommentPolicy) =
        encodeToPathPreserving(serializer, data, path, previousText)
}
