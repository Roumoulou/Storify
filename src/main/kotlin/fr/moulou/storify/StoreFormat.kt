// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import fr.moulou.storify.utils.DeepCopier
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import java.nio.file.Path

/**
 * Le contrat d'un format de fichier : une extension, l'encode/decode générique (C-09) et le copieur profond de ses stores (C-29).
 *
 * Le sérialiseur arrive en paramètre, matérialisé au site réifié de l'appelant (la factory, ou le
 * sucre ci-dessous) : c'est ce qui rend le contrat implémentable par un format tiers, là où une
 * méthode réifiée (donc inline, donc non virtuelle) ne pourrait pas être polymorphe.
 *
 * Le sérialiseur racine est résolu par le module par défaut de kotlinx.serialization ; les types
 * marqués `@Contextual` à l'intérieur de DATA restent résolus par le `serializersModule` du format
 * au moment de l'encodage ou du décodage.
 *
 * Un format tiers s'enregistre par [fr.moulou.storify.utils.StoreFormats.registerFormat] ; il est
 * alors résolu par l'extension du chemin, comme les formats fournis.
 */
interface StoreFormat {

    /** L'extension de fichier du format, sans le point (`json`, `toml`). */
    fun fileExtension(): String

    /** Décode [DATA] depuis le fichier [path]. */
    fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA

    /**
     * Encode [data] vers le fichier [path]. Le store garantit lui-même les dossiers parents avant
     * d'appeler ; un format utilisé hors store doit les créer, comme le font les formats fournis.
     */
    fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path)

    /**
     * Le copieur profond des stores de ce format (C-29) : l'arbre JSON au module par défaut, sauf pour un format bâti sur un `Json`, qui rend
     * un copieur sur ce `Json` afin que ses `@Contextual` et ses sérialiseurs écrits pour lui survivent à la copie.
     */
    fun deepCopier(): DeepCopier = DeepCopier.Default
}

/** Sucre réifié : matérialise le sérialiseur au site d'appel, puis passe par le contrat polymorphe. */
inline fun <reified DATA> StoreFormat.decodeFromPath(path: Path): DATA = decodeFromPath(serializer<DATA>(), path)

/** Sucre réifié : matérialise le sérialiseur au site d'appel, puis passe par le contrat polymorphe. */
inline fun <reified DATA> StoreFormat.encodeToPath(data: DATA, path: Path) = encodeToPath(serializer<DATA>(), data, path)
