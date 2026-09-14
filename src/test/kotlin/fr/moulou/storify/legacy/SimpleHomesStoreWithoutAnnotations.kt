package fr.moulou.storify.legacy

import fr.moulou.storify.Defaultable
import fr.moulou.storify.support.DatesBlock
import fr.moulou.storify.support.Home
import fr.moulou.storify.support.Location
import fr.moulou.storify.support.PrimitivesBlock
import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.Rule
import kotlinx.serialization.Serializable

/**
 * Le pendant « sans annotations » de [SimpleHomesStoreWithAnnotations] : aucune annotation de
 * store, tout (path, format, config) se donne explicitement à la factory. La couverture des
 * familles de types passe par [RandomData], qui rassemble déjà primitives, dates, collections,
 * tableaux et imbrications profondes.
 */
@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class SimpleHomesStoreWithoutAnnotations(
    var primitives: PrimitivesBlock,
    var dates: DatesBlock,
    var randomData: RandomData,

    /**
     * Clé : le nom du joueur
     * Valeur : la liste des maisons de ce joueur
     */
    var playersHomes: MutableMap<String, List<Home>>,

    /**
     * Clé : le nom du joueur
     * Valeur : Pair avec sa règle et les groupes qui lui sont associés
     */
    var associatedPlayersToRules: MutableMap<String, Pair<Rule, MutableSet<String>>>,
) {
    companion object : Defaultable<SimpleHomesStoreWithoutAnnotations> {
        override fun getDefault() = SimpleHomesStoreWithoutAnnotations(
            primitives = PrimitivesBlock.default(),
            dates = DatesBlock.default(),
            randomData = RandomData.default(),
            playersHomes = mutableMapOf(
                "Steve" to listOf(Home("base", Location(100.0, 64.0, 200.0, 0f, 0f, "minecraft:overworld"))),
                "Alex" to listOf(Home("nether_hub", Location(50.0, 70.0, -30.0, 90f, 0f, "minecraft:the_nether"))),
            ),
            associatedPlayersToRules = mutableMapOf(
                "Steve" to Pair(
                    Rule(true, 5, 60, 3, true, mutableSetOf("minecraft:overworld", "minecraft:the_nether")),
                    mutableSetOf("vip", "builder")
                ),
                "Alex" to Pair(
                    Rule(true, 3, 30, 5, true, mutableSetOf("minecraft:overworld")),
                    mutableSetOf("default")
                ),
            ),
        )
    }
}
