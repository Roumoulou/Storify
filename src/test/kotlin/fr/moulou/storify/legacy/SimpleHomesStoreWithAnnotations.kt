@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package fr.moulou.storify.legacy

import fr.moulou.storify.Defaultable
import fr.moulou.storify.StoreConfiguration
import fr.moulou.storify.StoreDefaultResource
import fr.moulou.storify.StorePath
import fr.moulou.storify.StoreValidator
import fr.moulou.storify.support.Home
import fr.moulou.storify.support.HomeValidator
import fr.moulou.storify.support.Location
import fr.moulou.storify.support.PrimitivesBlock
import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.Rule
import fr.moulou.storify.support.RuleValidator
import fr.moulou.storify.support.SimpleNestedData
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable

/** Regex pour le format "PlayerName#UUID" */
private val PLAYER_KEY_REGEX = Regex("^[a-zA-Z0-9_]{3,16}#[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Représente la configuration des maisons (homes).
 */
@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
@StorePath("C:\\temp\\SimpleHomesStoreWithAnnotations.json")
@StoreConfiguration(withAutoSave = false)
@StoreDefaultResource("SimpleHomesStoreWithAnnotations.json")
@StoreValidator(SimpleHomesStoreWithAnnotationsValidator::class)
data class SimpleHomesStoreWithAnnotations(

    var uselessString : String,

    var counter : Int,

    var primitives: PrimitivesBlock,

    var randomData: RandomData,

    var simpleNestedData: SimpleNestedData,

    /**
     * Clé : le nom du joueur
     * Valeur : la liste des maisons de ce joueur
     */
    var playersHomes: MutableMap<String, MutableList<Home>>,

    /**
     * Clé : Nom d'un group (VIP, DEFAULT, etc.)
     * Valeur : Pair avec une règle et la liste des joueurs associés à ce group et donc, cette "série" de règles
     */
    var associatedPlayersToRules: MutableMap<String, Pair<Rule, MutableSet<String>>>,

    ) {

    companion object : Defaultable<SimpleHomesStoreWithAnnotations> {
        override fun getDefault() = SimpleHomesStoreWithAnnotations(

            uselessString = "uselessString",

            counter = 0,

            primitives = PrimitivesBlock.default(),

            randomData = RandomData.default(),

            simpleNestedData = SimpleNestedData.default(),

            playersHomes = mutableMapOf(
                "Steve#8667ba71-b85a-4004-af54-457a9734eed7" to mutableListOf(Home("base", Location(100.0, 64.0, 200.0, 0f, 0f, "minecraft:overworld"))),
                "Alex#ec561538-f3fd-461d-aff5-086b22154bce" to mutableListOf(Home("nether_hub", Location(50.0, 70.0, -30.0, 90f, 0f, "minecraft:the_nether"))),
            ),
            associatedPlayersToRules = mutableMapOf(
                "VIP" to Pair(
                    Rule(true, 5, 60, 3, true, mutableSetOf("minecraft:overworld", "minecraft:the_nether")),
                    mutableSetOf("Alex#ec561538-f3fd-461d-aff5-086b22154bce")
                ),
                "DEFAULT" to Pair(
                    Rule(true, 3, 30, 5, true, mutableSetOf("minecraft:overworld")),
                    mutableSetOf("Steve#8667ba71-b85a-4004-af54-457a9734eed7")
                ),
            ),
        )
    }

}

class SimpleHomesStoreWithAnnotationsValidator : Validator<SimpleHomesStoreWithAnnotations> {

    private val primitivesValidator = PrimitivesBlock.Companion.PrimitivesBlockValidator()
    private val randomDataValidator = RandomData.Companion.RandomDataValidator()
    private val simpleNestedDataValidator = SimpleNestedData.Companion.SimpleNestedDataValidator()
    private val homeValidator = HomeValidator()
    private val ruleValidator = RuleValidator()

    override fun validate(data: SimpleHomesStoreWithAnnotations, ctx: ValidationContext) {

        // ── uselessString ──
        ctx.check(
            data.uselessString.isNotBlank(),
            "uselessString", "must not be blank", data.uselessString
        )

        // ── counter ──
        ctx.check(
            data.counter >= -10,
            "counter", "must not go below -10", data.counter
        )

        // ── primitives (nested) ──
        ctx.validateNested("primitives", data.primitives, primitivesValidator)

        // ── randomData (nested) ──
        ctx.validateNested("randomData", data.randomData, randomDataValidator)

        // ── simpleNestedData (nested) ──
        ctx.validateNested("simpleNestedData", data.simpleNestedData, simpleNestedDataValidator)

        // ── playersHomes ──
        // Vérifier le format des clés (PlayerName#UUID)
        data.playersHomes.forEach { (playerKey, homes) ->
            ctx.check(
                playerKey.matches(PLAYER_KEY_REGEX),
                "playersHomes",
                "key must follow 'PlayerName#UUID' format", playerKey
            )

//            // Pas de liste vide : si un joueur existe, il doit avoir au moins un home
//            ctx.check(
//                homes.isNotEmpty(),
//                "playersHomes[$playerKey]",
//                "player must have at least one home"
//            )

            // Pas de noms de home dupliqués pour un même joueur
            val homeNames = homes.map { it.name.lowercase() }
            ctx.check(
                homeNames.size == homeNames.toSet().size,
                "playersHomes[$playerKey]",
                "home names must be unique (found duplicates: ${homeNames.groupBy { it }.filter { it.value.size > 1 }.keys})"
            )

            // Valider chaque home
            homes.forEachIndexed { index, home ->
                val nestedPath = "playersHomes[$playerKey][$index]"
                ctx.validateNested(nestedPath, home, homeValidator)
            }
        }

        // ── associatedPlayersToRules ──
        data.associatedPlayersToRules.forEach { (groupName, pair) ->
            val (rule, players) = pair

            // Le nom du groupe ne doit pas être vide et doit être en UPPER_SNAKE_CASE
            ctx.check(
                groupName.isNotBlank(),
                "associatedPlayersToRules",
                "group name must not be blank", groupName
            )
            ctx.check(
                groupName.matches(Regex("^[A-Z][A-Z0-9_]*$")),
                "associatedPlayersToRules",
                "group name must be UPPER_SNAKE_CASE", groupName
            )

            // Valider la règle associée au groupe
            ctx.validateNested("associatedPlayersToRules[$groupName].rule", rule, ruleValidator)

            // Chaque joueur référencé doit suivre le format PlayerName#UUID
            players.forEach { playerKey ->
                ctx.check(
                    playerKey.matches(PLAYER_KEY_REGEX),
                    "associatedPlayersToRules[$groupName].players",
                    "player key must follow 'PlayerName#UUID' format", playerKey
                )
            }

            // Chaque joueur référencé doit exister dans playersHomes (cohérence des données)
            players.forEach { playerKey ->
                ctx.check(
                    data.playersHomes.containsKey(playerKey),
                    "associatedPlayersToRules[$groupName].players",
                    "references unknown player not found in playersHomes", playerKey
                )
            }

            // maxHomes de la règle doit être >= au nombre de homes réel de chaque joueur du groupe
            players.forEach { playerKey ->
                val playerHomes = data.playersHomes[playerKey]
                if (playerHomes != null) {
                    ctx.check(
                        playerHomes.size <= rule.maxHomes,
                        "associatedPlayersToRules[$groupName]",
                        "player '$playerKey' has ${playerHomes.size} homes but maxHomes is ${rule.maxHomes}"
                    )
                }
            }
        }

        // Vérifier qu'un joueur n'est pas dans plusieurs groupes à la fois
        val allPlayersInGroups = mutableMapOf<String, MutableList<String>>()
        data.associatedPlayersToRules.forEach { (groupName, pair) ->
            pair.second.forEach { player ->
                allPlayersInGroups.getOrPut(player) { mutableListOf() }.add(groupName)
            }
        }
        allPlayersInGroups.filter { it.value.size > 1 }.forEach { (player, groups) ->
            ctx.addObjectError(
                "Player '$player' is assigned to multiple groups: ${groups.joinToString()}"
            )
        }
    }
}