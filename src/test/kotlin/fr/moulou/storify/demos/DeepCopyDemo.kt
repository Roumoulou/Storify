// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.system.measureNanoTime

/*
 * La démo de C-29, à lire puis à lancer étape par étape (la flèche verte d'IntelliJ, ou la classe entière : les étapes
 * s'exécutent dans l'ordre de leur numéro). Le fil :
 *
 *   1. ce qu'est une copie profonde, et pourquoi un simple copy() ne suffit pas ;
 *   2. comment Storify la fabrique aujourd'hui : un aller-retour par CBOR (objet -> octets -> objet neuf) ;
 *   3. la même chose par un arbre JSON en mémoire (objet -> JsonElement -> objet neuf), sans produire de texte ;
 *   4. le cas qui casse : un sérialiseur écrit pour le JSON (celui d'AegisPerms) passe par l'arbre JSON et explose sous CBOR ;
 *   5. la panne d'avant C-29 dans un vrai store (même avec useDeepCopy = false), et son ouverture qui passe depuis ;
 *   6. le coût, mesuré, des deux véhicules.
 *
 * Les étapes 1 à 4 et 6 n'utilisent que kotlinx.serialization : elles se rejouent dans n'importe quel projet Kotlin.
 */

@Serializable
data class Player(var name: String, var homes: MutableList<String> = mutableListOf())

@Serializable
data class Roster(var players: MutableList<Player> = mutableListOf())

/** Une règle de droits : `"allow"` tout court, ou `{ "value": "allow", "note": "..." }` quand elle porte une note. */
@Serializable(with = RuleSerializer::class)
data class Rule(val value: String, val note: String? = null)

/**
 * Le sérialiseur écrit pour le JSON : il regarde la forme de l'élément (une chaîne ou un objet) avant de décider comment lire, et choisit la
 * forme courte ou longue à l'écriture. Pour cela il exige un JsonDecoder et un JsonEncoder ; devant CBOR, le cast échoue.
 */
object RuleSerializer : KSerializer<Rule> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Rule")

    override fun deserialize(decoder: Decoder): Rule {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> Rule(element.content)
            is JsonObject -> Rule(element.getValue("value").jsonPrimitive.content, element["note"]?.jsonPrimitive?.content)
            else -> error("Rule attend une chaîne ou un objet, pas $element")
        }
    }

    override fun serialize(encoder: Encoder, value: Rule) {
        val out = encoder as JsonEncoder
        if (value.note == null) out.encodeJsonElement(JsonPrimitive(value.value))
        else out.encodeJsonElement(buildJsonObject { put("value", value.value); put("note", value.note) })
    }
}

/** Le fichier de droits : une map de règles, avec une règle par défaut pour que la copie ait quelque chose à sérialiser. */
@Serializable
data class Permissions(var rules: MutableMap<String, Rule> = mutableMapOf("fly" to Rule("allow")))

@OptIn(ExperimentalSerializationApi::class)
@TestMethodOrder(MethodOrderer.DisplayName::class)
class DeepCopyDemoTest {

    private val cbor = Cbor { encodeDefaults = true }
    private val json = Json { encodeDefaults = true }

    @Test
    fun `étape 1, une affectation et le copy d'une data class partagent les objets internes`() {
        val steve = Player("Steve", mutableListOf("base"))
        val alias = steve          // le même objet
        val shallow = steve.copy() // un Player neuf, mais la MÊME liste dedans

        steve.homes.add("mine")

        println("alias.homes   = ${alias.homes}   (le même objet : il suit toutes les modifications)")
        println("shallow.homes = ${shallow.homes}   (copy() d'une data class : la liste est partagée)")
        // Un callback qui aurait reçu `shallow` comme « valeur d'avant » verrait déjà la valeur d'après.
        check(shallow.homes == listOf("base", "mine"))
    }

    @Test
    fun `étape 2, l'aller-retour CBOR fabrique un objet indépendant`() {
        val steve = Player("Steve", mutableListOf("base"))

        val bytes = cbor.encodeToByteArray(Player.serializer(), steve)  // l'objet devient des octets...
        val copy = cbor.decodeFromByteArray(Player.serializer(), bytes) // ... et les octets redeviennent un objet neuf, liste comprise

        steve.homes.add("mine")

        println("octets CBOR (${bytes.size}) : ${bytes.joinToString(" ") { "%02x".format(it) }}")
        println("copy.homes = ${copy.homes}   (indépendante : la mutation de l'original ne la touche pas)")
        check(copy.homes == listOf("base"))
    }

    @Test
    fun `étape 3, l'aller-retour par arbre JSON fait la même chose sans produire de texte`() {
        val steve = Player("Steve", mutableListOf("base"))

        val tree: JsonElement = json.encodeToJsonElement(Player.serializer(), steve) // un arbre d'objets en mémoire, pas une chaîne
        val copy = json.decodeFromJsonElement(Player.serializer(), tree)

        steve.homes.add("mine")

        println("arbre JSON : $tree")
        println("copy.homes = ${copy.homes}")
        check(copy.homes == listOf("base"))
    }

    @Test
    fun `étape 4, un sérialiseur écrit pour le JSON passe par l'arbre JSON et casse sous CBOR`() {
        val text = """{ "rules": { "fly": "allow", "tp": { "value": "deny", "note": "sauf les ops" } } }"""
        val permissions = json.decodeFromString(Permissions.serializer(), text)
        println("décodé depuis le texte JSON : $permissions")

        // Par l'arbre JSON : le décodeur EST un JsonDecoder, le sérialiseur est content.
        val copy = json.decodeFromJsonElement(Permissions.serializer(), json.encodeToJsonElement(Permissions.serializer(), permissions))
        println("copie par arbre JSON : $copy")
        check(copy == permissions && copy !== permissions)

        // Par CBOR : l'encodeur n'est pas un JsonEncoder, le cast du sérialiseur explose.
        val failure = runCatching { cbor.encodeToByteArray(Permissions.serializer(), permissions) }.exceptionOrNull()
        println("copie par CBOR : ${failure?.let { "${it::class.simpleName}: ${it.message}" } ?: "passée (inattendu)"}")
        check(failure is ClassCastException)
    }

    @Test
    fun `étape 5, Storify depuis C-29, le même sérialiseur n'empêche plus l'ouverture du store, avec ou sans useDeepCopy`() {
        // Avant C-29 : ClassCastException dès l'ouverture, née de `_lastSavedData = deepCopyFn(_data)` dans initData, un aller-retour CBOR
        // inconditionnel, même avec useDeepCopy = false. Depuis C-29, les copies du store passent par l'arbre JSON du format, que ce
        // sérialiseur accepte ; et depuis C-41 l'ouverture n'en prend plus aucune : la première copie de la racine est celle que réclame
        // un callback de save, à son enregistrement. Les deux ouvertures passent, et cette copie aussi.
        val withoutCopies = runCatching { StoreFactory.createFromConstructor<Permissions>(newStorePath("permissions.json").toString(), config = StoreConfig(withAutoSave = false, useDeepCopy = false)).close() }.exceptionOrNull()
        val withCopies = runCatching { StoreFactory.createFromConstructor<Permissions>(newStorePath("permissions-copied.json").toString(), config = StoreConfig(withAutoSave = false)).use { it.registerOnSave { } } }.exceptionOrNull()

        println("ouverture sans useDeepCopy : ${withoutCopies?.let { "${it::class.simpleName}: ${it.message}" } ?: "passée"}")
        println("ouverture avec useDeepCopy, puis la copie de la racine pour un callback de save : ${withCopies?.let { "${it::class.simpleName}: ${it.message}" } ?: "passées"}")
        check(withoutCopies == null && withCopies == null)
    }

    @Test
    fun `étape 6, le coût, CBOR contre arbre JSON sur un objet de deux cents joueurs`() {
        val roster = Roster((1..200).map { Player("player$it", mutableListOf("base", "mine", "farm")) }.toMutableList())
        fun viaCbor() = cbor.decodeFromByteArray(Roster.serializer(), cbor.encodeToByteArray(Roster.serializer(), roster))
        fun viaTree() = json.decodeFromJsonElement(Roster.serializer(), json.encodeToJsonElement(Roster.serializer(), roster))

        repeat(2_000) { viaCbor(); viaTree() } // la chauffe du JIT
        val iterations = 2_000
        val cborNs = measureNanoTime { repeat(iterations) { viaCbor() } } / iterations
        val treeNs = measureNanoTime { repeat(iterations) { viaTree() } } / iterations
        println("copie de 200 joueurs : CBOR %.1f µs, arbre JSON %.1f µs".format(cborNs / 1_000.0, treeNs / 1_000.0))
    }
}