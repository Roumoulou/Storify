// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.ResourceData
import fr.moulou.storify.support.ValidatedResourceData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path

/*
 * La démo de C-40 : le premier lancement d'un store né d'une ressource embarquée (createFromResource). Le mod livre dans son jar un fichier par
 * défaut, souvent commenté pour guider l'admin, et la lib le copie vers le chemin du store quand celui-ci n'existe pas encore. La question : le
 * fichier que l'admin trouve après ce premier lancement est-il la ressource du jar ? Chaque étape imprime la ressource, puis le fichier, puis
 * le verdict à l'octet près.
 *
 * Avant C-40, non : le store croyait ses données nées des défauts du code et, la validation passée, réécrivait la copie par l'encodeur du
 * format. Une ressource JSON ressortait réindentée (42 octets devenus 45), une ressource JSON5 ou TOML commentée perdait tous ses commentaires
 * (258 octets devenus 41, 236 devenus 30), et la copie ne restait intacte qu'invalide, ou avec createIfMissing à false. Depuis : la copie est
 * la ressource, à l'octet, posée par l'écrivain atomique, et le store ne la réécrit plus.
 *
 *   1. une ressource JSON : le fichier est la ressource, indentation et saut de ligne final compris ;
 *   2. une ressource JSON5 commentée : les commentaires arrivent chez l'admin, et la sauvegarde préservante (C-26) les garde ensuite ;
 *   3. une ressource TOML commentée : ils arrivent aussi, mais la première sauvegarde réécrit le fichier entier, la limite connue du TOML ;
 *   4. une ressource invalide : la copie reste sur le disque, éditable, et le message dit qu'elle vient de la ressource, avec la ligne.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class ResourceCopyDemoTest {

    private fun resourceBytes(name: String): ByteArray = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "ressource de test introuvable : $name" }.use { it.readBytes() }

    private fun show(label: String, bytes: ByteArray) {
        println("    $label (${bytes.size} octets)")
        bytes.decodeToString().lines().forEach { println("        | $it") }
    }

    /** Imprime la ressource puis le fichier, et rend vrai s'ils sont identiques à l'octet. */
    private fun sameAsResource(resource: String, path: Path): Boolean {
        val shipped = resourceBytes(resource)
        val onDisk = Files.readAllBytes(path)
        show("la ressource du jar, $resource", shipped)
        show("le fichier après le premier lancement", onDisk)
        return shipped.contentEquals(onDisk).also { println("    identique à l'octet : ${if (it) "oui" else "non"}") }
    }

    /** Le premier lancement : le fichier n'existe pas, le store naît de la ressource puis se ferme. ResourceData porte sa config par annotation (ni auto-save ni validation). */
    private fun firstLaunch(fileName: String, resource: String): Path =
        newStorePath(fileName).also { path -> StoreFactory.createFromResource<ResourceData>(path.toString(), resource).close() }

    /** Un second lancement : le fichier existe, il gagne sur la ressource ; le mod change une valeur et sauvegarde. */
    private fun changeLevelAndSave(path: Path, resource: String) = StoreFactory.createFromResource<ResourceData>(path.toString(), resource).use { store ->
        store.set(ResourceData::level, 43)
        store.saveImmediate()
    }

    @Test
    fun `étape 1, depuis C-40, le fichier du premier lancement est la ressource JSON, à l'octet`() {
        val path = firstLaunch("config.json", "resource-data_default.json")
        check(sameAsResource("resource-data_default.json", path))
    }

    @Test
    fun `étape 2, depuis C-40, une ressource JSON5 commentée garde ses commentaires, et la première sauvegarde les préserve`() {
        val path = firstLaunch("config.json5", "resource-data_commented.json5")
        check(sameAsResource("resource-data_commented.json5", path))

        changeLevelAndSave(path, "resource-data_commented.json5")
        show("le fichier après un set(level, 43) et une sauvegarde", Files.readAllBytes(path))
        check("// Le niveau de départ" in Files.readString(path) && "level: 43" in Files.readString(path))
    }

    @Test
    fun `étape 3, depuis C-40, une ressource TOML commentée garde les siens, jusqu'à la première sauvegarde`() {
        val path = firstLaunch("config.toml", "resource-data_commented.toml")
        check(sameAsResource("resource-data_commented.toml", path))

        changeLevelAndSave(path, "resource-data_commented.toml")
        show("le fichier après un set(level, 43) et une sauvegarde", Files.readAllBytes(path)) // en TOML, la sauvegarde réécrit le fichier entier
        check("#" !in Files.readString(path))
    }

    @Test
    fun `étape 4, une ressource invalide reste sur le disque, et depuis C-40 le message dit d'où elle vient`() {
        val path = newStorePath("config.json")
        val failure = runCatching { StoreFactory.createFromResource<ValidatedResourceData>(path.toString(), "resource-data_invalid.json").close() }.exceptionOrNull()
        println("    ouverture : ${failure?.let { it::class.simpleName } ?: "passée (inattendu)"}")
        failure?.message?.lines()?.forEach { println("        $it") }
        check(sameAsResource("resource-data_invalid.json", path))
        check(failure?.message?.contains("copied from the default resource") == true)
    }
}
