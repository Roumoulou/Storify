// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.utils

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * L'écrivain atomique de Storify, public depuis C-34 : ce que les stores font à chaque sauvegarde, offert à tout fichier qu'un consommateur
 * écrit hors d'un store (un export, une copie de secours, un catalogue généré). Un fichier écrit par [write] est toujours une version
 * entière : un crash au milieu de l'écriture ne touche jamais la cible.
 */
object AtomicFiles {

    private val log = LoggerFactory.getLogger(AtomicFiles::class.java)

    /**
     * Écrit [target] via un temporaire voisin unique (`<nom>.<8 hexadécimaux>.tmp`), force le flush disque, puis remplace la cible par
     * déplacement atomique (repli non atomique loggué si le système de fichiers ne sait pas faire). Les dossiers parents sont garantis avant
     * d'appeler [writeTo] ; si [writeTo] lève, le temporaire est supprimé, la cible reste intacte, et l'exception remonte.
     */
    fun write(target: Path, writeTo: (Path) -> Unit) {
        target.toAbsolutePath().parent?.createDirectories()
        val temp = target.resolveSibling(tempFileName(target.fileName.toString()))
        try {
            writeTo(temp)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                log.warn("[Storify] Atomic move unsupported for '{}': falling back to a non-atomic replace", target)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    /** Le nom d'un temporaire atomique pour [fileName] : `<nom>.<8 hexadécimaux>.tmp`, les huit premiers caractères d'un UUID aléatoire. */
    fun tempFileName(fileName: String): String = "$fileName.${UUID.randomUUID().toString().substring(0, 8)}.tmp"

    /** Le motif exact des temporaires que [tempFileName] produit pour [fileName] : le seul que [sweepOrphanTemps] reconnaît (C-28). */
    fun ownTempPattern(fileName: String): Regex = Regex("^${Regex.escape(fileName)}\\.[0-9a-f]{8}\\.tmp$")

    /**
     * Balaye les temporaires orphelins d'un crash passé pour chacun des [targets], au motif de [tempFileName] et à lui seul : un temporaire
     * étranger, `<nom>.tmp` écrit par une autre application par exemple, n'est jamais touché. Silencieux sur un dossier absent ou illisible.
     */
    fun sweepOrphanTemps(vararg targets: Path) {
        targets.groupBy { it.toAbsolutePath().parent }.forEach { (directory, files) ->
            if (directory == null || !directory.exists()) return@forEach
            val own = files.map { ownTempPattern(it.fileName.toString()) }
            runCatching {
                Files.newDirectoryStream(directory) { candidate -> own.any { it.matches(candidate.fileName.toString()) } }
                    .use { stream -> stream.forEach { runCatching { Files.deleteIfExists(it) } } }
            }
        }
    }
}
