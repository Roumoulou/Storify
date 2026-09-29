// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import fr.moulou.storify.utils.AtomicFiles
import fr.moulou.storify.utils.DeepCopier
import fr.moulou.storify.validation.ErrorLineLocator
import fr.moulou.storify.validation.ValidationErrorEnricher
import fr.moulou.storify.validation.ValidationResult
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import java.nio.file.Path

/**
 * Le contrat d'un format de fichier : une extension, l'encode/decode générique (C-09), le copieur profond de ses stores (C-29), et la
 * localisation des lignes d'erreur comme la validation d'un fichier (C-32).
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

    /** Le localisateur de lignes des erreurs de validation (C-32) : `null` quand le format ne sait pas retrouver un chemin dans son texte. */
    fun lineLocator(): ErrorLineLocator? = null

    /**
     * Valide le fichier [path] sans ouvrir de store (C-32) : décodé par ce format, validé par [validator], les erreurs enrichies des lignes
     * quand le format sait les localiser. Un fichier qui ne se décode pas lève, comme au chargement d'un store.
     */
    fun <DATA : Any> validateFile(path: Path, deserializer: DeserializationStrategy<DATA>, validator: Validator<DATA>): ValidationResult =
        ValidationErrorEnricher.validate(this, path, decodeFile(deserializer, path), validator)
}

/** Sucre réifié : matérialise le sérialiseur au site d'appel, puis passe par le contrat polymorphe. */
inline fun <reified DATA> StoreFormat.decodeFromPath(path: Path): DATA = decodeFromPath(serializer<DATA>(), path)

/** Sucre réifié : matérialise le sérialiseur au site d'appel, puis passe par le contrat polymorphe. */
inline fun <reified DATA> StoreFormat.encodeToPath(data: DATA, path: Path) = encodeToPath(serializer<DATA>(), data, path)

/** Sucre réifié : matérialise le sérialiseur au site d'appel, puis valide le fichier par [StoreFormat.validateFile]. */
inline fun <reified DATA : Any> StoreFormat.validateFile(path: Path, validator: Validator<DATA>): ValidationResult = validateFile(path, serializer<DATA>(), validator)

/**
 * Décode [path] par ce format, toute panne de lecture ou de parseur enveloppée dans une [StoreDecodeException] qui nomme le fichier (C-33) :
 * la voie de tout décodage fait pour un consommateur (ouverture, rechargement, `validateFile`, sidecar, ressource). Le contrat brut
 * [StoreFormat.decodeFromPath] reste tel quel.
 */
@PublishedApi
internal fun <DATA> StoreFormat.decodeFile(deserializer: DeserializationStrategy<DATA>, path: Path): DATA =
    try {
        decodeFromPath(deserializer, path)
    } catch (e: StorifyException) {
        throw e
    } catch (e: Exception) {
        throw StoreDecodeException(path, this, e)
    }

/**
 * Encode [data] vers [path] par [AtomicFiles.write] (C-34) : temporaire voisin, flush, déplacement atomique ; le fichier est toujours une
 * version entière, comme une sauvegarde de store. Le contrat [StoreFormat.encodeToPath], lui, écrit directement.
 */
fun <DATA> StoreFormat.encodeToPathAtomically(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) = AtomicFiles.write(path) { temp -> encodeToPath(serializer, data, temp) }

/** Sucre réifié : matérialise le sérialiseur au site d'appel, puis écrit par [encodeToPathAtomically]. */
inline fun <reified DATA> StoreFormat.encodeToPathAtomically(data: DATA, path: Path) = encodeToPathAtomically(serializer<DATA>(), data, path)
