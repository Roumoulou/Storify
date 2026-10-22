// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/**
 * Les commentaires du code (C-64) : ce que [StoreComment] déclare, relevé dans le descripteur d'un sérialiseur, chemin par chemin, pour qu'un format
 * qui écrit des commentaires les pose au-dessus des clés. Un chemin est la suite des noms de propriété tels que le fichier les écrit (`@SerialName`
 * compris), vide pour la racine.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object StoreComments {

    /** Un chemin commenté : [keys] depuis la racine, vide pour elle, et [lines], une par ligne de commentaire. */
    internal data class Entry(val keys: List<String>, val lines: List<String>)

    /**
     * Les chemins commentés de [descriptor], la racine en premier puis l'ordre du descripteur, parent avant enfant. Seules les classes et les objets
     * se descendent : une liste, une map, un type polymorphe, contextuel ou énuméré arrêtent la descente (leurs éléments n'ont pas de propriété à
     * annoter) ; une value class se traverse sans segment, le fichier n'écrivant que sa valeur ; un type récursif est coupé à sa seconde occurrence
     * sur le chemin.
     */
    fun collect(descriptor: SerialDescriptor): List<Entry> {
        val entries = mutableListOf<Entry>()
        linesOf(descriptor.annotations)?.let { entries += Entry(emptyList(), it) }
        descend(descriptor, emptyList(), mutableListOf(), entries)
        return entries
    }

    private fun descend(descriptor: SerialDescriptor, keys: List<String>, ancestors: MutableList<String>, into: MutableList<Entry>) {
        if (descriptor.isInline && descriptor.elementsCount == 1) {
            descend(descriptor.getElementDescriptor(0), keys, ancestors, into)
            return
        }
        if (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT) return
        if (descriptor.serialName in ancestors) return
        ancestors += descriptor.serialName
        for (index in 0 until descriptor.elementsCount) {
            val childKeys = keys + descriptor.getElementName(index)
            linesOf(descriptor.getElementAnnotations(index))?.let { into += Entry(childKeys, it) }
            descend(descriptor.getElementDescriptor(index), childKeys, ancestors, into)
        }
        ancestors.removeAt(ancestors.lastIndex)
    }

    /** Les lignes du [StoreComment] parmi [annotations], une ligne qui porte un retour à la ligne coupée en autant de lignes ; null sans annotation. */
    private fun linesOf(annotations: List<Annotation>): List<String>? =
        annotations.filterIsInstance<StoreComment>().firstOrNull()?.lines?.flatMap { it.lines() }
}
