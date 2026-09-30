// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.support

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Paths

/*
 * La lecture des fichiers .class que le compilateur produit pour les tests (C-45). Ce qu'une classe référence d'une autre se lit dans sa
 * table des constantes : c'est le moyen de voir ce qu'une fonction inline de la lib range chez son appelant, donc dans le jar d'un mod.
 */

/** Les fichiers .class de [type] et de ses classes synthétiques (`<Type>$...`), par nom de fichier, lus dans le dossier de classes des tests. */
fun classFilesOf(type: Class<*>): Map<String, ByteArray> {
    val classesRoot = Paths.get(type.protectionDomain.codeSource.location.toURI())
    val packageDirectory = classesRoot.resolve(type.packageName.replace('.', '/'))
    return Files.list(packageDirectory).use { files ->
        files.filter { file -> file.fileName.toString().let { it == "${type.simpleName}.class" || it.startsWith("${type.simpleName}$") } }
            .toList()
            .associate { it.fileName.toString() to Files.readAllBytes(it) }
    }
}

/** Les membres (méthodes et champs) de la classe [owner], donnée par son nom interne (`fr/moulou/...`), que ce fichier .class référence. */
fun referencedMembers(classFile: ByteArray, owner: String): Set<String> {
    val input = DataInputStream(ByteArrayInputStream(classFile))
    input.skipBytes(8) // le nombre magique et la version
    val count = input.readUnsignedShort()
    val texts = HashMap<Int, String>()
    val classNames = HashMap<Int, Int>()
    val memberNames = HashMap<Int, Int>()
    val references = ArrayList<Pair<Int, Int>>()
    var index = 1
    while (index < count) {
        when (val tag = input.readUnsignedByte()) {
            1 -> texts[index] = input.readUTF()
            3, 4 -> input.skipBytes(4)
            5, 6 -> { input.skipBytes(8); index++ } // un long ou un double occupe deux entrées
            7 -> classNames[index] = input.readUnsignedShort()
            8, 16, 19, 20 -> input.skipBytes(2)
            9, 10, 11 -> references.add(input.readUnsignedShort() to input.readUnsignedShort())
            12 -> { memberNames[index] = input.readUnsignedShort(); input.skipBytes(2) }
            15 -> input.skipBytes(3)
            17, 18 -> input.skipBytes(4)
            else -> error("unknown constant pool tag $tag")
        }
        index++
    }
    return references.filter { (classIndex, _) -> texts[classNames[classIndex]] == owner }.mapNotNull { (_, memberIndex) -> texts[memberNames[memberIndex]] }.toSortedSet()
}
