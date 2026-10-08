// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

/*
 * La démo de C-48 : ce qu'une sauvegarde coûte, geste par geste, et ce qu'elle bloque pendant ce temps. Une sauvegarde encode vers un
 * temporaire, force le flush disque, puis déplace le temporaire sur la cible, le tout sous le read lock du store : pendant tout ce temps, un
 * set ou un mutate venu d'un autre fil (le fil du serveur, si l'auto-save tourne) attend le write lock.
 *
 * L'hypothèse de départ visait le flush. La mesure l'a démentie : sur 5 000 joueurs (794 Ko), le flush coûtait 1,7 ms et le déplacement
 * 0,5 ms, mais l'encodage 88,7 ms, parce que JsonFormat écrivait sur le flux nu du fichier, où chaque petit morceau produit par kotlinx
 * partait au système en appel séparé ; un mutate lancé pendant la sauvegarde attendait 97 ms. Depuis C-48, JsonFormat lit et écrit par un
 * flux tamponné : même texte, même atomicité, une douzaine d'écritures au lieu de milliers. Sortir le flush du verrou, l'idée de départ, est
 * abandonné : il n'y a rien à y gagner.
 *
 *   1. la part de chaque geste, mesurée sur une racine de cinq mille joueurs : l'encodage, le flush, le déplacement ;
 *   2. pendant une sauvegarde, un mutate sur un autre fil attend la fin de la sauvegarde : prouvé par l'ordre, le mutate couru après la fin de
 *      l'encodage que le format témoin signale (C-59), les durées imprimées sans assertion, parce qu'un rapport entre deux temps mesurés
 *      rougissait la suite une fois sur deux ou trois ;
 *   3. le même JSON écrit par quatre chemins : le flux nu d'avant C-48, un tampon de 8 Ko, de 64 Ko, une chaîne écrite d'un coup ;
 *   4. le même JSON lu par trois chemins : kotlinx lit déjà par blocs, le tampon y gagne peu.
 */

/** Un format qui prévient quand un encodage commence et quand il finit : le témoin du moment où le store, en pleine sauvegarde, tient son read lock. */
class SignallingFormat(private val delegate: StoreFormat) : StoreFormat by delegate {
    @Volatile var encodingStarted = CountDownLatch(1)
    @Volatile var encodingFinished = false
    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        encodingStarted.countDown()
        delegate.encodeToPath(serializer, data, path)
        encodingFinished = true // encore sous le read lock de la sauvegarde : un mutate, qui prend le write lock, ne peut pas courir avant
    }
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class SaveLockDemoTest {

    private fun players() = (1..5_000).map { Player("player$it", mutableListOf("base", "mine", "farm")) }

    @Test
    fun `étape 1, la part de chaque geste dans une sauvegarde de cinq mille joueurs`() {
        val format = JsonFormat()
        val data = Roster(players().toMutableList())
        val target = newStorePath("roster.json")
        val temp = target.resolveSibling("roster.json.tmp")
        repeat(5) { format.encodeToPath(Roster.serializer(), data, temp); Files.deleteIfExists(temp) } // la chauffe du JIT

        val runs = 20
        var encodeNs = 0L; var forceNs = 0L; var moveNs = 0L
        repeat(runs) {
            encodeNs += measureNanoTime { format.encodeToPath(Roster.serializer(), data, temp) }
            forceNs += measureNanoTime { FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) } }
            moveNs += measureNanoTime { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        }
        println("    le fichier : ${Files.size(target) / 1024} Ko")
        println("    l'encodage vers le temporaire : %6.1f ms".format(encodeNs / runs / 1e6))
        println("    le flush disque (force)       : %6.1f ms".format(forceNs / runs / 1e6))
        println("    le déplacement atomique       : %6.1f ms".format(moveNs / runs / 1e6))
    }

    @Test
    fun `étape 2, pendant une sauvegarde, un mutate sur un autre fil attend la fin de la sauvegarde`() {
        val format = SignallingFormat(JsonFormat())
        val store = StoreFactory.createFromConstructor<Roster>(newStorePath("roster.json").toString(), format = format, config = StoreConfig(withAutoSave = false))
        store.mutate(Roster::players) { it.addAll(players()) }
        store.saveImmediate() // la chauffe

        val aloneNs = measureNanoTime { store.mutate(Roster::players) { } }
        format.encodingStarted = CountDownLatch(1)
        format.encodingFinished = false
        var saveNs = 0L
        val saver = thread { saveNs = measureNanoTime { store.saveImmediate() } }
        format.encodingStarted.await() // la sauvegarde tient le read lock et encode
        var ranAfterEncoding = false
        val waitedNs = measureNanoTime { store.mutate(Roster::players) { ranAfterEncoding = format.encodingFinished } }
        saver.join()
        store.close()

        println("    un mutate seul                        : %6.2f ms".format(aloneNs / 1e6))
        println("    la sauvegarde, sur son fil            : %6.1f ms".format(saveNs / 1e6))
        println("    le mutate lancé pendant la sauvegarde : %6.1f ms d'attente".format(waitedNs / 1e6))
        println("    le mutate a couru après la fin de l'encodage : $ranAfterEncoding")
        // Le mutate a attendu la sauvegarde, pas seulement son propre verrou : vrai par construction du verrou (BaseStore.save encode, flushe et
        // déplace sous dataLock.read, et le write lock du mutate attend), faux le jour où une sauvegarde encoderait hors de lui. Les durées
        // ci-dessus ne s'affirment pas : un rapport entre deux temps mesurés rougissait la suite une fois sur deux ou trois (C-59).
        check(ranAfterEncoding)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `étape 3, le même encodage par quatre chemins d'écriture`() {
        val json = JsonFormat().underlyingJson()
        val data = Roster(players().toMutableList())
        val temp = newStorePath("roster.json")
        val paths = listOf<Pair<String, () -> Unit>>(
            "encodeToStream sur le flux nu du fichier (JsonFormat avant C-48)  " to { Files.newOutputStream(temp).use { json.encodeToStream(Roster.serializer(), data, it) } },
            "encodeToStream sur un flux tamponné de 8 Ko                      " to { BufferedOutputStream(Files.newOutputStream(temp), 8_192).use { json.encodeToStream(Roster.serializer(), data, it) } },
            "encodeToStream sur un flux tamponné de 64 Ko                     " to { BufferedOutputStream(Files.newOutputStream(temp), 65_536).use { json.encodeToStream(Roster.serializer(), data, it) } },
            "encodeToString puis une seule écriture des octets                " to { Files.write(temp, json.encodeToString(Roster.serializer(), data).toByteArray()) },
        )
        for ((label, write) in paths) {
            repeat(5) { write() } // la chauffe du JIT
            val runs = 20
            val ns = measureNanoTime { repeat(runs) { write() } } / runs
            println("    %s : %6.1f ms".format(label, ns / 1e6))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `étape 4, le même décodage par trois chemins de lecture`() {
        val format = JsonFormat()
        val json = format.underlyingJson()
        val path = newStorePath("roster.json")
        format.encodeToPath(Roster.serializer(), Roster(players().toMutableList()), path)
        val paths = listOf<Pair<String, () -> Roster>>(
            "decodeFromStream sur le flux nu du fichier (JsonFormat avant C-48)  " to { Files.newInputStream(path).use { json.decodeFromStream(Roster.serializer(), it) } },
            "JsonFormat.decodeFromPath, depuis C-48                             " to { format.decodeFromPath(Roster.serializer(), path) },
            "decodeFromStream sur un flux tamponné de 64 Ko                     " to { BufferedInputStream(Files.newInputStream(path), 65_536).use { json.decodeFromStream(Roster.serializer(), it) } },
            "readString puis decodeFromString                                   " to { json.decodeFromString(Roster.serializer(), Files.readString(path)) },
        )
        for ((label, read) in paths) {
            repeat(5) { read() } // la chauffe du JIT
            val runs = 20
            val ns = measureNanoTime { repeat(runs) { read() } } / runs
            println("    %s : %6.1f ms".format(label, ns / 1e6))
        }
    }
}
