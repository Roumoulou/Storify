// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.ScalarData
import fr.moulou.storify.support.classFilesOf
import fr.moulou.storify.support.referencesInto
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/*
 * La démo de C-46 : l'ouverture d'un store vue de l'appelant, ce qu'un mod embarque quand il écrit StoreFactory.createFromConstructor(...)
 * ou StoreFactory.create(...).
 *
 * Avant C-46, les huit fabriques étaient inline de bout en bout, comme le pipeline d'update avant C-45 : la lecture des cinq annotations,
 * le choix du format selon l'enum, la construction de la config annotée et l'appel au constructeur de BaseStore se compilaient chez
 * l'appelant. OpeningCaller.class pesait 15 363 octets pour deux ouvertures (25 915 avec ses six classes synthétiques) et portait 49
 * références à la lib, dont les 11 attributs de @StoreConfiguration lus un par un : un mod compilé ce jour-là gardait pour toujours cette
 * liste d'attributs et cette table des formats. Depuis : les fabriques ne matérialisent plus que DATA::class et le sérialiseur, puis
 * appellent un point d'entrée ordinaire de StoreFactory, un par source de données initiales.
 *
 *   1. le fichier .class d'un appelant qui ouvre deux stores : sa taille, et tout ce qu'il référence de la lib, classe par classe.
 */

/** L'appelant type : un mod qui ouvre sa config et ses données, rien de plus. */
class OpeningCaller {
    fun openConfig(path: String) = StoreFactory.createFromConstructor<ScalarData>(path, config = StoreConfig(withAutoSave = false))
    fun openData(path: String) = StoreFactory.create<PlainData>(path)
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class StoreOpeningDemoTest {

    @Test
    fun `étape 1, depuis C-46, un appelant de deux ouvertures n'embarque que l'appel aux points d'entrée`() {
        val classFiles = classFilesOf(OpeningCaller::class.java)
        val main = classFiles.getValue("OpeningCaller.class")
        println("    OpeningCaller.class : ${main.size} octets pour deux ouvertures ; avec ses ${classFiles.size - 1} classes synthétiques : ${classFiles.values.sumOf { it.size }} octets")

        val references = classFiles.values.flatMap { referencesInto(it, "fr/moulou/storify/") }
            .filterNot { it.startsWith("demos/") || it.startsWith("support/") } // ses propres classes, et les data classes qu'il ouvre
            .toSortedSet()
        println("    références à la lib (${references.size}), classe par classe :")
        references.groupBy({ it.substringBeforeLast('.') }, { it.substringAfterLast('.') }).toSortedMap().forEach { (owner, members) ->
            println("        %-24s %s".format(owner, members.joinToString(", ")))
        }
        check(references.all { it.startsWith("core/StoreFactory.") || it == "core/StoreConfig.<init>" }) // la factory, et la config que l'appelant construit lui-même
    }
}
