// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.Defaultable
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-54 : ce qu'un consommateur déclare aujourd'hui quand la forme d'un fichier naît d'une valeur, pas d'une classe. Le scénario est
 * celui de ManyManyCommands : cinq fichiers de règles, chacun décrit par la table de sa famille, dont découlent son sérialiseur
 * (RulesSerializer(table)) et ses valeurs livrées (table.shipped). Les huit create* de StoreFactory tirent pourtant tout de la classe de DATA :
 * le sérialiseur de serializer<DATA>(), les données initiales de son constructeur sans argument, de son companion ou d'une classe Defaultable
 * instanciée de même. Aucune porte ne prend ces deux-là en valeurs.
 *
 *   1. aujourd'hui, la voie qui marche : une classe par famille, @Serializable(with = ...) vers un object Format imbriqué et un constructeur
 *      sans argument, cinq classes de même forme sans autre rôle que de donner prise à la réflexion ;
 *   2. aujourd'hui, la seule voie sans classe : une Map<String, Int> ouverte par createFromDefaultable, sous le sérialiseur générique de la
 *      Map, qui perd le schéma de la table : une clé inconnue passe sans un mot, quand le sérialiseur de la table la refuse ;
 *   3. depuis C-54 : la même ouverture par createFromProvider, le sérialiseur et les valeurs livrées donnés en valeurs, sans classe par
 *      famille, le schéma gardé : la faute de frappe est refusée à l'ouverture.
 */

/** La table d'une famille de règles : ses clés et la valeur livrée de chacune. Chez ManyManyCommands, une table par famille, cinq en tout. */
class RulesTable(val family: String, val shipped: Map<String, Int>)

val HOMES = RulesTable("homes", mapOf("max-homes" to 3, "delay" to 5, "cooldown" to 30))
val WARPS = RulesTable("warps", mapOf("max-warps" to 10, "delay" to 3))

/** Le sérialiseur d'un fichier de règles, déduit de sa table : un objet aux clés de la table, un entier chacune ; une clé que la table ne connaît pas est refusée. */
class RulesSerializer(private val table: RulesTable) : KSerializer<Map<String, Int>> {

    private val map = MapSerializer(String.serializer(), Int.serializer())

    override val descriptor: SerialDescriptor get() = map.descriptor

    override fun deserialize(decoder: Decoder): Map<String, Int> {
        val read = decoder.decodeSerializableValue(map)
        val unknown = read.keys - table.shipped.keys
        if (unknown.isNotEmpty()) throw SerializationException("Unknown key(s) $unknown in the rules of '${table.family}' (known: ${table.shipped.keys})")
        return read
    }

    override fun serialize(encoder: Encoder, value: Map<String, Int>) = encoder.encodeSerializableValue(map, value)
}

// ─── Étape 1 : la voie d'aujourd'hui, une classe par famille ───────────────────────────────────

/** Ce qu'un fichier de règles porte. La classe de base des documents de famille. */
abstract class RulesDocument(val values: Map<String, Int>)

/** Le sérialiseur d'une classe de famille : celui de sa table, enveloppé dans la classe pour que serializer<DATA>() ait quelque chose à trouver. */
abstract class RulesDocumentSerializer<DOCUMENT : RulesDocument>(table: RulesTable, private val wrap: (Map<String, Int>) -> DOCUMENT) : KSerializer<DOCUMENT> {

    private val rules = RulesSerializer(table)

    override val descriptor: SerialDescriptor get() = rules.descriptor

    override fun deserialize(decoder: Decoder): DOCUMENT = wrap(decoder.decodeSerializableValue(rules))

    override fun serialize(encoder: Encoder, value: DOCUMENT) = encoder.encodeSerializableValue(rules, value.values)
}

/** La classe des homes, sans autre rôle que de donner prise à la réflexion : le sérialiseur par l'annotation, les valeurs livrées par le constructeur sans argument. */
@Serializable(with = HomesRulesDocument.Format::class)
class HomesRulesDocument(values: Map<String, Int> = HOMES.shipped) : RulesDocument(values) {

    object Format : RulesDocumentSerializer<HomesRulesDocument>(HOMES, ::HomesRulesDocument)
}

/** La classe des warps, de la même forme. ManyManyCommands en déclare cinq comme elle, une par famille. */
@Serializable(with = WarpsRulesDocument.Format::class)
class WarpsRulesDocument(values: Map<String, Int> = WARPS.shipped) : RulesDocument(values) {

    object Format : RulesDocumentSerializer<WarpsRulesDocument>(WARPS, ::WarpsRulesDocument)
}

// ─── Étape 2 : la seule voie sans classe, une Map sous son sérialiseur générique ──────────────

/** Les valeurs livrées des homes, par la seule porte qui accepte une racine sans classe : un Defaultable externe, instancié par réflexion, donc sans la table en paramètre. */
class HomesShippedValues : Defaultable<Map<String, Int>> {
    override fun getDefault(): Map<String, Int> = HOMES.shipped
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class ProviderFactoryDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    private fun oneLine(path: Path) = path.readText().lines().joinToString(" ") { it.trim() }

    @Test
    fun `étape 1, aujourd'hui, une classe par famille pour que la réflexion ait quelque chose à trouver`() {
        val homes = newStorePath("homes.json")
        StoreFactory.createFromConstructor<HomesRulesDocument>(homes.toString(), config = noAutoSave).use { store ->
            println("    homes.json, créé depuis le constructeur sans argument de HomesRulesDocument : ${oneLine(homes)}")
            check(store.data.values == HOMES.shipped)
        }
        val warps = newStorePath("warps.json")
        StoreFactory.createFromConstructor<WarpsRulesDocument>(warps.toString(), config = noAutoSave).use { store ->
            println("    warps.json, créé depuis celui de WarpsRulesDocument : ${oneLine(warps)}")
            check(store.data.values == WARPS.shipped)
        }
        val declared = listOf(HomesRulesDocument::class, HomesRulesDocument.Format::class, WarpsRulesDocument::class, WarpsRulesDocument.Format::class)
        println("    déclaré pour deux fichiers : ${declared.size} types, ${declared.joinToString { it.simpleName!! }} ; pour les cinq familles de ManyManyCommands, dix")
    }

    @Test
    fun `étape 2, aujourd'hui, la seule voie sans classe perd le schéma de la table`() {
        val path = newStorePath("homes.json")
        path.writeText("""{ "max-home": 1, "delay": 5, "cooldown": 30 }""") // « max-home » : la faute de frappe d'un admin
        StoreFactory.createFromDefaultable<Map<String, Int>, HomesShippedValues>(path.toString(), config = noAutoSave).use { store ->
            println("    sous le sérialiseur générique de la Map, la faute passe : ${store.data}")
            check("max-home" in store.data && "max-homes" !in store.data) // le mod lirait un max-homes absent, sans un mot
        }
        val refused = runCatching { JsonFormat().decodeFromPath(RulesSerializer(HOMES), path) }.exceptionOrNull()
        println("    le sérialiseur de la table, lui, la refuse : ${refused?.message}")
        check(refused is SerializationException)
        // ... mais aucune fabrique ne le prend : les huit create* tirent le sérialiseur de serializer<DATA>(), donc de la classe de DATA.
    }

    @Test
    fun `étape 3, depuis C-54, le sérialiseur et les valeurs livrées se donnent en valeurs, sans classe par famille`() {
        val path = newStorePath("homes.json")
        StoreFactory.createFromProvider(RulesSerializer(HOMES), path.toString(), config = noAutoSave) { HOMES.shipped }.use { store ->
            println("    homes.json, créé depuis la table : ${oneLine(path)}")
            check(store.data == HOMES.shipped)
        }
        path.writeText("""{ "max-home": 1, "delay": 5, "cooldown": 30 }""")
        val refused = runCatching { StoreFactory.createFromProvider(RulesSerializer(HOMES), path.toString(), config = noAutoSave) { HOMES.shipped } }.exceptionOrNull()
        println("    la faute de frappe, refusée à l'ouverture : ${refused?.message?.lines()?.first()}")
        check(refused is StoreDecodeException)
    }
}
