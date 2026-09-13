package fr.moulou.storify

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationException
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ═══ Le domaine : un mod de homes, comme on l'écrirait pour un vrai serveur ═════════════════════
//
// Deux stores, deux vies : la CONFIG (TOML, éditée par l'admin, écrite au moment où on la modifie)
// et les DONNÉES (JSON du monde, vivantes, auto-sauvées). C'est le découpage type d'un mod Storify.

/**
 * La configuration du mod : le fichier TOML que l'admin du serveur édite.
 * Tous les champs ont une valeur par défaut : le store se crée par `createFromConstructor`,
 * la voie la plus naturelle pour une config de mod.
 */
@Serializable
data class HomesModConfig(

    /** Nombre maximal de homes par joueur ; `/sethome` refuse au-delà. */
    var maxHomesPerPlayer: Int = 3,

    /** Le délai avant téléportation, en secondes (le joueur ne doit pas bouger pendant ce temps). */
    var teleportDelaySeconds: Int = 3,

    /** Le temps minimal entre deux téléportations d'un même joueur, en secondes. */
    var cooldownSeconds: Int = 60,

    /** Annuler la téléportation si le joueur bouge pendant le délai. */
    var cancelOnMove: Boolean = true,

    /** Les dimensions depuis lesquelles `/sethome` est permis, au format `namespace:path`. */
    var allowedDimensions: MutableSet<String> = mutableSetOf("minecraft:overworld", "minecraft:the_nether"),
)

/** Le validator de la config : les bornes, et une règle croisée (le cooldown englobe le délai). */
class HomesModConfigValidator : Validator<HomesModConfig> {

    private val dimensionFormat = Regex("^[a-z_][a-z0-9_]*:[a-z_][a-z0-9_/]*$")

    override fun validate(data: HomesModConfig, ctx: ValidationContext) {
        ctx.check(data.maxHomesPerPlayer in 1..100, "maxHomesPerPlayer", "must be between 1 and 100", data.maxHomesPerPlayer)
        ctx.check(data.teleportDelaySeconds >= 0, "teleportDelaySeconds", "must not be negative", data.teleportDelaySeconds)
        ctx.check(data.cooldownSeconds >= data.teleportDelaySeconds, "cooldownSeconds", "must be at least the teleport delay", data.cooldownSeconds)
        ctx.check(data.allowedDimensions.isNotEmpty(), "allowedDimensions", "must contain at least one dimension")
        data.allowedDimensions.forEach { ctx.check(it.matches(dimensionFormat), "allowedDimensions", "invalid dimension format (expected 'namespace:path')", it) }
    }
}

/**
 * Les données vivantes du mod : le JSON du monde. Racine volontairement imbriquée (une map de
 * dossiers joueurs, chacun portant sa map de homes) : le terrain de jeu des `mutate` et `setIn`.
 * Le validator vient de l'annotation : l'autre voie de résolution, que la config n'exerce pas.
 */
@Serializable
@StoreValidator(HomesModDataValidator::class)
data class HomesModData(

    /** Clé : l'UUID du joueur (en chaîne) ; valeur : son dossier. */
    var players: MutableMap<String, PlayerHomes> = mutableMapOf(),

    /** Compteur global de téléportations. SHALLOW : copier profondément un Long serait du gaspillage. */
    @StoreUpdatePolicy(UpdatePolicy.SHALLOW)
    var totalTeleports: Long = 0,
)

@Serializable
data class PlayerHomes(

    /** Le pseudo au dernier passage (il peut changer ; l'UUID de la clé, jamais). */
    var name: String = "?",

    /** L'horodatage de la dernière téléportation (epoch en secondes) : la matière première du cooldown. */
    var lastTeleportAtEpochSecond: Long = 0,

    /** Les homes du joueur, par nom. */
    var homes: MutableMap<String, Home> = mutableMapOf(),
)

@Serializable
data class Home(
    var dimension: String = "minecraft:overworld",
    var x: Double = 0.0,
    var y: Double = 64.0,
    var z: Double = 0.0,
)

class HomesModDataValidator : Validator<HomesModData> {
    override fun validate(data: HomesModData, ctx: ValidationContext) {
        ctx.check(data.totalTeleports >= 0, "totalTeleports", "must not be negative", data.totalTeleports)
        data.players.forEach { (uuid, record) ->
            ctx.check(record.lastTeleportAtEpochSecond >= 0, "players[$uuid].lastTeleportAtEpochSecond", "must not be negative", record.lastTeleportAtEpochSecond)
            record.homes.forEach { (homeName, _) -> ctx.check(homeName.isNotBlank(), "players[$uuid].homes", "home name must not be blank") }
        }
    }
}

// ═══ La visite guidée ════════════════════════════════════════════════════════════════════════════

/**
 * La visite guidée de Storify, au propre, sur le domaine du mod de homes : cinq démos autonomes,
 * chacune centrée sur une facette de la lib (naissance et défauts, édition de config sous callbacks,
 * logique métier avec cooldown, auto-save réel, édition à chaud revalidée).
 *
 * Chaque démo repart d'un dossier vierge : le ménage de [cleanSlate] efface ce qu'une exécution
 * précédente aurait laissé (fichiers de stores, sidecars, temporaires). Les fichiers vivent dans
 * `build\tmp\storify-demo`, que `gradlew clean` emporte.
 */
class HomesModDemo {

    private val demoDirectory: Path = Paths.get("build", "tmp", "storify-demo")
    private val configPath: Path = demoDirectory.resolve("config").resolve("homesmod.toml")
    private val dataPath: Path = demoDirectory.resolve("world").resolve("data").resolve("homes.json")

    /** Le réglage type d'une config : validée, sans auto-save (elle s'écrit quand on la modifie), observable (SNAPSHOT). */
    private val configSettings = StoreConfig(withValidation = true, withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    /** Le réglage type des données quand la démo n'a pas besoin d'auto-save : validées, observables. */
    private val dataSettings = StoreConfig(withValidation = true, withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    /**
     * LE MÉNAGE DU DÉBUT. Si une exécution précédente a laissé des fichiers, la démo repartirait
     * d'un état inconnu (un TOML déjà modifié, un JSON avec d'anciens joueurs). On efface donc tout
     * le dossier de démo, systématiquement, avant chaque test : c'est le prix d'un départ déterministe,
     * et le bon réflexe pour toute démo qui écrit sur disque.
     */
    @BeforeEach
    fun cleanSlate() {
        if (Files.exists(demoDirectory)) {
            Files.walk(demoDirectory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    // ═══ Démo 1 : la naissance d'un store ════════════════════════════════════════════════════════

    @Test
    fun `la config naît avec ses défauts, validée, dans un TOML lisible`() {
        // createFromConstructor : les données initiales viennent des défauts du constructeur.
        // Le format n'est pas précisé : l'extension .toml le résout toute seule ; les dossiers
        // manquants sont créés à l'écriture, et la validation tourne AVANT que le fichier naisse
        // (des défauts invalides ne toucheraient jamais le disque).
        val store = StoreFactory.createFromConstructor<HomesModConfig>(
            stringPath = configPath.toString(),
            config = configSettings,
            validator = HomesModConfigValidator(),
        )

        assertEquals(3, store.data.maxHomesPerPlayer)
        assertTrue(Files.exists(configPath)) // le fichier initial est né...
        assertTrue(configPath.readText().contains("maxHomesPerPlayer = 3")) // ... en TOML lisible par l'admin

        store.close() // la fin de vie propre : un store se ferme comme un flux (il est AutoCloseable)
    }

    // ═══ Démo 2 : la config s'édite sous callbacks et se persiste immédiatement ═════════════════

    @Test
    fun `la config se modifie sous callbacks et s'écrit d'un saveImmediate`() {
        val store = StoreFactory.createFromConstructor<HomesModConfig>(configPath.toString(), config = configSettings, validator = HomesModConfigValidator())

        // L'observatoire : en SNAPSHOT, chaque callback reçoit un avant/après figé, qu'on peut
        // logguer ou comparer sans craindre qu'il change sous nos pieds.
        val updates = mutableListOf<Operation<HomesModConfig>>()
        store.registerOnUpdate { updates.add(it) }

        store.set(HomesModConfig::cooldownSeconds, 120)
        store.saveImmediate() // pas d'auto-save sur une config : on écrit au moment où l'on modifie

        val operation = assertInstanceOf(SetOperation::class.java, updates.single())
        assertEquals(60, operation.old.valueOrNull)  // l'avant...
        assertEquals(120, operation.new.valueOrNull) // ... et l'après
        assertTrue(configPath.readText().contains("cooldownSeconds = 120"))

        store.close()
    }

    // ═══ Démo 3 : la logique du mod : sethome, limite, cooldown, callback ciblé ═════════════════
    // Le pattern recommandé : les CONTRÔLES MÉTIER (la limite de homes, le cooldown) se font AVANT
    // de muter ; la validation de la lib garde la frontière du fichier, pas chaque geste interne.

    @Test
    fun `sethome respecte la limite de la config, home respecte le cooldown`() {
        val config = StoreFactory.createFromConstructor<HomesModConfig>(configPath.toString(), config = configSettings, validator = HomesModConfigValidator())
        // Pas de validator explicite ici : celui de @StoreValidator sur HomesModData est résolu tout seul.
        val homes = StoreFactory.createFromConstructor<HomesModData>(dataPath.toString(), config = dataSettings)

        val steve = "8667ba71-b85a-4004-af54-457a9734eed7"
        var clockSeconds = 1_000L // l'horloge simulée de la démo (en vrai : l'horloge du serveur)

        // « /sethome » : le contrôle métier d'abord, la mutation typée ensuite (getOrPut crée le dossier du joueur).
        fun setHome(uuid: String, playerName: String, homeName: String, home: Home): Boolean {
            val existing = homes.data.players[uuid]?.homes ?: emptyMap()
            if (homeName !in existing && existing.size >= config.data.maxHomesPerPlayer) return false
            homes.mutate(HomesModData::players) { players ->
                players.getOrPut(uuid) { PlayerHomes(name = playerName) }.homes[homeName] = home
            }
            return true
        }

        // « /home » : le cooldown d'abord ; s'il passe, l'horodatage (setIn en navigation) et le compteur (set).
        fun teleport(uuid: String, homeName: String): Boolean {
            val record = homes.data.players[uuid] ?: return false
            if (homeName !in record.homes) return false
            if (clockSeconds - record.lastTeleportAtEpochSecond < config.data.cooldownSeconds) return false
            homes.setIn(PlayerHomes::lastTeleportAtEpochSecond, clockSeconds) { players.getValue(uuid) }
            homes.set(HomesModData::totalTeleports, homes.data.totalTeleports + 1)
            return true
        }

        // Trois homes passent, le quatrième bute sur la limite lue dans la config.
        assertTrue(setHome(steve, "Steve", "base", Home(x = 100.0, z = 200.0)))
        assertTrue(setHome(steve, "Steve", "mine", Home(y = 12.0)))
        assertTrue(setHome(steve, "Steve", "ferme", Home(z = -40.0)))
        assertFalse(setHome(steve, "Steve", "plage", Home())) // maxHomesPerPlayer = 3

        // Le callback ciblé : il ne parle que quand LE compteur bouge, pas à chaque update du store.
        var counterCallbacks = 0
        homes.registerOnUpdateOn(HomesModData::totalTeleports) { counterCallbacks++ }

        assertTrue(teleport(steve, "base"))  // premier voyage : le cooldown démarre
        assertFalse(teleport(steve, "mine")) // trop tôt : refusé sans avoir rien muté
        clockSeconds += 61
        assertTrue(teleport(steve, "mine"))  // le cooldown (60 s) est purgé

        assertEquals(2, homes.data.totalTeleports)
        assertEquals(2, counterCallbacks)

        // La fin : close() fait la sauvegarde d'adieu du dirty restant, puis libère tout.
        homes.close()
        config.close()
        assertTrue(dataPath.readText().contains("\"base\""))
    }

    // ═══ Démo 4 : l'auto-save, le vrai ═══════════════════════════════════════════════════════════

    @Test
    fun `l'auto-save persiste tout seul, puis close libère le monde`() {
        // Intervalle court pour la démo ; en vrai : 30 s ou plus. À noter : même la policy par
        // défaut (SKIP, silencieuse pour les callbacks) marque le store dirty : la persistance
        // ne dépend jamais de l'observation.
        val homes = StoreFactory.createFromConstructor<HomesModData>(dataPath.toString(), config = StoreConfig(withAutoSave = true, autoSaveIntervalMs = 100))

        val saves = mutableListOf<Operation<HomesModData>>()
        homes.registerOnSave { saves.add(it) }

        homes.mutate(HomesModData::players) { players -> players.getOrPut("alex-uuid") { PlayerHomes(name = "Alex") } }

        // On attend le tick (généreusement : 5 s de marge pour un intervalle de 100 ms).
        val deadline = System.currentTimeMillis() + 5_000
        while (saves.none { it is SaveOperation<*> && it.trigger == SaveTrigger.AUTO_SAVE } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue(saves.any { it is SaveOperation<*> && it.trigger == SaveTrigger.AUTO_SAVE })
        assertTrue(dataPath.readText().contains("Alex"))

        homes.close() // sans close(), le thread du planificateur retiendrait la JVM pour toujours
    }

    // ═══ Démo 5 : l'édition à chaud, gardée par la revalidation ═════════════════════════════════

    @Test
    fun `un fichier édité à la main est revalidé au reload, la mémoire reste intacte`() {
        val homes = StoreFactory.createFromConstructor<HomesModData>(dataPath.toString(), config = dataSettings)
        homes.mutate(HomesModData::players) { players -> players.getOrPut("steve-uuid") { PlayerHomes(name = "Steve") } }
        homes.saveImmediate()

        // L'admin se trompe : un compteur négatif, bien formé mais invalide.
        dataPath.writeText(dataPath.readText().replace("\"totalTeleports\": 0", "\"totalTeleports\": -7"))

        assertThrows(ValidationException::class.java) { homes.reloadFromFile() } // le reload refuse...
        assertEquals(0, homes.data.totalTeleports) // ... et la mémoire n'a pas bougé

        // L'admin corrige, le reload passe.
        dataPath.writeText(dataPath.readText().replace("\"totalTeleports\": -7", "\"totalTeleports\": 42"))
        homes.reloadFromFile()
        assertEquals(42, homes.data.totalTeleports)

        homes.close()
    }
}
