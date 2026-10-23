// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.CommentPolicy
import fr.moulou.storify.Json5Format
import fr.moulou.storify.StoreComment
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-64 : les commentaires depuis le code. Une data class annotée `@StoreComment` écrit ses commentaires dans le fichier JSON5 qu'elle
 * crée (étape 1) ; l'admin retouche le fichier, et sous FILE_WINS, le défaut, ses commentaires gagnent, seule une clé neuve reçoit le commentaire
 * du code (étape 2) ; sous CODE_WINS, chaque sauvegarde remet les commentaires du code au-dessus des clés qu'il commente, sans toucher aux autres
 * (étape 3).
 */
@TestMethodOrder(MethodOrderer.DisplayName::class)
class CommentsDemoTest {

    private val format = Json5Format()

    @Test
    fun `étape 1, un fichier neuf porte les commentaires du code`() {
        val path = newStorePath("demo.json5")
        format.encodeToPath(DemoConfig.serializer(), DemoConfig(), path)
        show("le fichier que le mod écrit", path.readText())
    }

    @Test
    fun `étape 2, FILE_WINS, l'admin gagne, seule une clé neuve reçoit le commentaire du code`() {
        val path = newStorePath("demo.json5")
        val edited = "{\n    // Changé par l'admin : on dit bonjour\n    greeting: 'Bonjour',\n    maxHomes: 10,\n    debug: true,\n}\n"
        path.writeText(edited)
        show("le fichier après l'admin (son commentaire, maxHomes nu, delays effacé)", edited)

        format.encodeToPathPreserving(DemoConfig.serializer(), DemoConfig(greeting = "Bonjour", maxHomes = 10, debug = true), path, edited, CommentPolicy.FILE_WINS)
        show("après la sauvegarde du mod, FILE_WINS", path.readText())
    }

    @Test
    fun `étape 3, CODE_WINS, les commentaires du code reviennent, ceux des autres clés restent`() {
        val path = newStorePath("demo.json5")
        val edited = "{\n    // Changé par l'admin : on dit bonjour\n    greeting: 'Bonjour',\n    maxHomes: 10,\n    // à moi, le code ne commente pas debug\n    debug: true,\n    delays: {\n        warmup: 5,\n        cooldown: 30,\n    },\n}\n"
        path.writeText(edited)
        show("le fichier après l'admin", edited)

        format.encodeToPathPreserving(DemoConfig.serializer(), DemoConfig(greeting = "Bonjour", maxHomes = 10, debug = true, delays = DemoDelays(5, 30)), path, edited, CommentPolicy.CODE_WINS)
        show("après la sauvegarde du mod, CODE_WINS", path.readText())
    }

    private fun show(title: String, text: String) {
        println("── $title ──")
        println(text.trimEnd())
    }

    @Serializable
    @StoreComment("La config du mod Demo : éditez les valeurs, pas les clés")
    data class DemoConfig(
        @StoreComment("Le message d'accueil, affiché à la connexion") var greeting: String = "Bienvenue",
        @StoreComment("Entre 1 et 100", "Au-delà, la commande refuse") var maxHomes: Int = 3,
        var debug: Boolean = false,
        @StoreComment("Les délais, en secondes") var delays: DemoDelays = DemoDelays(),
    )

    @Serializable
    data class DemoDelays(@StoreComment("Avant la téléportation") var warmup: Int = 3, var cooldown: Int = 60)
}
