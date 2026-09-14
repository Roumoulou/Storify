@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package fr.moulou.storify.legacy

import fr.moulou.storify.Defaultable
import fr.moulou.storify.StoreDefaultResource
import fr.moulou.storify.StorePath
import fr.moulou.storify.StoreValidator
import fr.moulou.storify.support.Home
import fr.moulou.storify.support.HomeValidator
import fr.moulou.storify.support.HomeValidator2
import fr.moulou.storify.support.Location
import fr.moulou.storify.support.PrimitivesBlock
import fr.moulou.storify.support.PrimitivesBlock2
import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.RandomData2
import fr.moulou.storify.support.Rule
import fr.moulou.storify.support.RuleValidator
import fr.moulou.storify.support.RuleValidator2
import fr.moulou.storify.support.SimpleNestedData
import fr.moulou.storify.support.SimpleNestedData2
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
@StorePath("C:\\temp\\SimpleHomesStoreWithAnnotations2.json")
@StoreDefaultResource("SimpleHomesStoreWithAnnotations2.json")
@StoreValidator(SimpleHomesStoreWithAnnotationsValidator2::class)
data class SimpleHomesStoreWithAnnotations2(

    var uselessString2: String = "uselessString",

    var counter2: Int = 0,

    var primitives2: PrimitivesBlock2 = PrimitivesBlock2(),

    var randomData2: RandomData2 = RandomData2(),

    var simpleNestedData2: SimpleNestedData2 = SimpleNestedData2(),

    /**
     * Clé : le nom du joueur
     * Valeur : la liste des maisons de ce joueur
     */
    var playersHomes2: MutableMap<String, MutableList<Home>> = mutableMapOf(
        "Steve#8667ba71-b85a-4004-af54-457a9734eed7" to mutableListOf(Home("base", Location(100.0, 64.0, 200.0, 0f, 0f, "minecraft:overworld"))),
        "Alex#ec561538-f3fd-461d-aff5-086b22154bce" to mutableListOf(Home("nether_hub", Location(50.0, 70.0, -30.0, 90f, 0f, "minecraft:the_nether"))),
    ),

    /**
     * Clé : Nom d'un group (VIP, DEFAULT, etc.)
     * Valeur : Pair avec une règle et la liste des joueurs associés à ce group et donc, cette "série" de règles
     */
    var associatedPlayersToRules2: MutableMap<String, Pair<Rule, MutableSet<String>>> = mutableMapOf(
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


class SimpleHomesStoreWithAnnotationsValidator2 : Validator<SimpleHomesStoreWithAnnotations2> {

    private val primitivesValidator2 = PrimitivesBlock2.Companion.PrimitivesBlockValidator2()
    private val randomDataValidator2 = RandomData2.Companion.RandomDataValidator2()
    private val simpleNestedDataValidator2 = SimpleNestedData2.Companion.SimpleNestedDataValidator2()
    private val homeValidator2 = HomeValidator2()
    private val ruleValidator2 = RuleValidator2()

    override fun validate(data: SimpleHomesStoreWithAnnotations2, ctx: ValidationContext) {

        // ── uselessString ──
        ctx.check(
            data.uselessString2.isNotBlank(),
            "uselessString2", "must not be blank", data.uselessString2
        )

        // ── counter ──
        ctx.check(
            data.counter2 >= -10,
            "counter2", "must not go below -10", data.counter2
        )

        // ── primitives (nested) ──
        ctx.validateNested("primitives2", data.primitives2, primitivesValidator2)

        // ── randomData (nested) ──
        ctx.validateNested("randomData2", data.randomData2, randomDataValidator2)

        // ── simpleNestedData (nested) ──
        ctx.validateNested("simpleNestedData2", data.simpleNestedData2, simpleNestedDataValidator2)

        // ── playersHomes ──
        // Vérifier le format des clés (PlayerName#UUID)
        data.playersHomes2.forEach { (playerKey, homes) ->
            ctx.check(
                playerKey.matches(PLAYER_KEY_REGEX),
                "playersHomes2",
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
                "playersHomes2[$playerKey]",
                "home names must be unique (found duplicates: ${homeNames.groupBy { it }.filter { it.value.size > 1 }.keys})"
            )

            // Valider chaque home
            homes.forEachIndexed { index, home ->
                val nestedPath = "playersHomes2[$playerKey][$index]"
                ctx.validateNested(nestedPath, home, homeValidator2)
            }
        }

        // ── associatedPlayersToRules ──
        data.associatedPlayersToRules2.forEach { (groupName, pair) ->
            val (rule, players) = pair

            // Le nom du groupe ne doit pas être vide et doit être en UPPER_SNAKE_CASE
            ctx.check(
                groupName.isNotBlank(),
                "associatedPlayersToRules2",
                "group name must not be blank", groupName
            )
            ctx.check(
                groupName.matches(Regex("^[A-Z][A-Z0-9_]*$")),
                "associatedPlayersToRules2",
                "group name must be UPPER_SNAKE_CASE", groupName
            )

            // Valider la règle associée au groupe
            ctx.validateNested("associatedPlayersToRules2[$groupName].rule", rule, ruleValidator2)

            // Chaque joueur référencé doit suivre le format PlayerName#UUID
            players.forEach { playerKey ->
                ctx.check(
                    playerKey.matches(PLAYER_KEY_REGEX),
                    "associatedPlayersToRules2[$groupName].players",
                    "player key must follow 'PlayerName#UUID' format", playerKey
                )
            }

            // Chaque joueur référencé doit exister dans playersHomes (cohérence des données)
            players.forEach { playerKey ->
                ctx.check(
                    data.playersHomes2.containsKey(playerKey),
                    "associatedPlayersToRules2[$groupName].players",
                    "references unknown player not found in playersHomes", playerKey
                )
            }

            // maxHomes de la règle doit être >= au nombre de homes réel de chaque joueur du groupe
            players.forEach { playerKey ->
                val playerHomes = data.playersHomes2[playerKey]
                if (playerHomes != null) {
                    ctx.check(
                        playerHomes.size <= rule.maxHomes,
                        "associatedPlayersToRules2[$groupName]",
                        "player '$playerKey' has ${playerHomes.size} homes but maxHomes is ${rule.maxHomes}"
                    )
                }
            }
        }

        // Vérifier qu'un joueur n'est pas dans plusieurs groupes à la fois
        val allPlayersInGroups = mutableMapOf<String, MutableList<String>>()
        data.associatedPlayersToRules2.forEach { (groupName, pair) ->
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