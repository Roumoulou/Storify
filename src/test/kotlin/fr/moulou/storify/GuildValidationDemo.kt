package fr.moulou.storify

import fr.moulou.storify.core.*
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// ══════════════════════════════════════════════════════════════
// DATA CLASSES — propres, zéro logique de validation
// ══════════════════════════════════════════════════════════════

/** Coordonnées d'un lieu dans le monde */
@Serializable
data class Location(
    var world: String,
    var x: Double,
    var y: Double,
    var z: Double
)

/** Un upgrade achetable pour la base de guilde */
@Serializable
data class GuildUpgrade(
    var name: String,
    var level: Int,
    var maxLevel: Int
)

/** La base physique de la guilde */
@Serializable
data class GuildBase(
    var location: Location,
    var upgrades: MutableList<GuildUpgrade>
)

/** Un membre de la guilde */
@Serializable
data class GuildMember(
    var username: String,
    var role: String,
    var contribution: Int
)

/** Permissions associées à un rang */
@Serializable
data class RankPermissions(
    var canInvite: Boolean,
    var canKick: Boolean,
    var canBuild: Boolean
)

/** La guilde complète — classe racine du store */
@Serializable
@StorePath("C:\\temp\\guild.json")
@StoreConfiguration(withAutoSave = false, withValidation = true, validateOnUpdate = true)
@StoreValidator(GuildDataValidator::class)
data class GuildData(
    var name: String,
    var tag: String,
    var level: Int,
    var maxMembers: Int,
    var motd: String,
    var leaderName: String,
    var members: MutableList<GuildMember>,
    var base: GuildBase,
    var ranks: MutableMap<String, RankPermissions>
) {
    companion object : Defaultable<GuildData> {
        override fun getDefault() = GuildData(
            name       = "Les Conquérants",
            tag        = "LCQ",
            level      = 1,
            maxMembers = 10,
            motd       = "Bienvenue dans la guilde !",
            leaderName = "MoulouKing",
            members    = mutableListOf(
                GuildMember("MoulouKing", "leader", 500),
                GuildMember("Alex_99", "officer", 120),
                GuildMember("NoobSlayer", "member", 30)
            ),
            base = GuildBase(
                location = Location("world", 100.0, 64.0, -200.0),
                upgrades = mutableListOf(
                    GuildUpgrade("Forge", 2, 5),
                    GuildUpgrade("Entrepôt", 1, 3)
                )
            ),
            ranks = mutableMapOf(
                "leader"  to RankPermissions(canInvite = true, canKick = true, canBuild = true),
                "officer" to RankPermissions(canInvite = true, canKick = false, canBuild = true),
                "member"  to RankPermissions(canInvite = false, canKick = false, canBuild = false)
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════
// VALIDATORS — chaque data class a son validator dédié
// ══════════════════════════════════════════════════════════════

// ── 1. Champ simple : string non vide, range numérique ──
class LocationValidator : Validator<Location> {
    override fun validate(data: Location, ctx: ValidationContext) {
        ctx.check(data.world.isNotBlank(), "world", "must not be blank", data.world)
        ctx.check(data.y in -64.0..320.0, "y", "must be between -64 and 320", data.y)
    }
}

// ── 2. Validation cross-field (level vs maxLevel) ──
class GuildUpgradeValidator : Validator<GuildUpgrade> {
    override fun validate(data: GuildUpgrade, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.check(data.level in 0..data.maxLevel, "level", "must be between 0 and maxLevel (${data.maxLevel})", data.level)
        ctx.check(data.maxLevel >= 1, "maxLevel", "must be at least 1", data.maxLevel)
    }
}

// ── 3. Nested object + nested collection (validateNested + validateEach) ──
class GuildBaseValidator : Validator<GuildBase> {
    override fun validate(data: GuildBase, ctx: ValidationContext) {
        ctx.validateNested("location", data.location, LocationValidator())
        ctx.validateEach("upgrades", data.upgrades, GuildUpgradeValidator())
    }
}

// ── 4. Champ simple + enum-like string validation ──
class GuildMemberValidator : Validator<GuildMember> {
    companion object {
        val VALID_ROLES = setOf("leader", "officer", "member")
    }

    override fun validate(data: GuildMember, ctx: ValidationContext) {
        ctx.check(data.username.isNotBlank(), "username", "must not be blank", data.username)
        ctx.check(data.username.length in 3..16, "username", "must be between 3 and 16 characters", data.username)
        ctx.check(data.role in VALID_ROLES, "role", "must be one of $VALID_ROLES", data.role)
        ctx.check(data.contribution >= 0, "contribution", "must not be negative", data.contribution)
    }
}

// ── 5. Validation de Map (itération manuelle sur les entries) ──
class RankPermissionsValidator : Validator<RankPermissions> {
    override fun validate(data: RankPermissions, ctx: ValidationContext) {
        // Un rang qui peut kick doit aussi pouvoir invite
        if (data.canKick && !data.canInvite) {
            ctx.addObjectError("a rank that can kick must also be able to invite")
        }
    }
}

// ── 6. Validator racine : tous les cas combinés ──
class GuildDataValidator : Validator<GuildData> {
    override fun validate(data: GuildData, ctx: ValidationContext) {

        // ── Champs simples ──
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.check(data.name.length in 3..32, "name", "must be between 3 and 32 characters", data.name)

        // ── Pattern string (tag 2-4 majuscules) ──
        ctx.check(data.tag.matches(Regex("^[A-Z]{2,4}$")), "tag", "must be 2-4 uppercase letters", data.tag)

        // ── Ranges numériques ──
        ctx.check(data.level in 1..100, "level", "must be between 1 and 100", data.level)
        ctx.check(data.maxMembers in 1..50, "maxMembers", "must be between 1 and 50", data.maxMembers)

        // ── Longueur string ──
        ctx.check(data.motd.length <= 200, "motd", "must be 200 characters or less", data.motd.length)

        // ── Object-level : taille de la collection vs champ ──
        if (data.members.size > data.maxMembers) {
            ctx.addObjectError("members count (${data.members.size}) exceeds maxMembers (${data.maxMembers})")
        }

        // ── Cross-field : le leader doit exister dans les membres ──
        ctx.check(
            data.members.any { it.username == data.leaderName },
            "leaderName", "must reference an existing member", data.leaderName
        )

        // ── Unicité dans une collection ──
        val duplicates = data.members.groupBy { it.username }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            ctx.addError("members", "contains duplicate usernames: $duplicates", duplicates)
        }

        // ── Collection : validateEach ──
        ctx.validateEach("members", data.members, GuildMemberValidator())

        // ── Nested object (qui contient lui-même un nested + une collection) ──
        ctx.validateNested("base", data.base, GuildBaseValidator())

        // ── Map : validation manuelle des entries ──
        data.ranks.forEach { (rankName, perms) ->
            ctx.validateNested("ranks[$rankName]", perms, RankPermissionsValidator())
        }

        // ── Cross-field Map/Collection : chaque membre doit avoir un rang existant ──
        data.members.forEach { member ->
            if (member.role !in data.ranks) {
                ctx.addError("ranks", "member '${member.username}' has role '${member.role}' but no matching rank definition exists", member.role)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// TESTS — chaque test démontre un cas de validation
// ══════════════════════════════════════════════════════════════

class GuildValidationDemo {

    private fun freshStore(): BaseStore<GuildData> {
        java.io.File("C:\\temp\\guild.json").delete()
        return StoreFactory.create<GuildData>()
    }

    // ── Création valide ──

    @Test
    fun `1 — valid guild creation`() {
        val store = freshStore()
        println("Guild created: ${store.data.name} [${store.data.tag}]")
        println("Members: ${store.data.members.map { it.username }}")
    }

    // ── Champ simple rejeté ──

    @Test
    fun `2 — reject blank guild name`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.set(GuildData::name, "")
        assertEquals("Les Conquérants", store.data.name) // refusé pour de vrai depuis C-05 (validateOnUpdate)
        println("Name unchanged: '${store.data.name}'")
    }

    // ── Pattern string rejeté (tag invalide) ──

    @Test
    fun `3 — reject invalid tag format`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.set(GuildData::tag, "lowercase")
        println("Tag unchanged: '${store.data.tag}'")
    }

    // ── Range numérique rejeté ──

    @Test
    fun `4 — reject level out of range`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.set(GuildData::level, 999)
        println("Level unchanged: ${store.data.level}")
    }

    // ── Nested object : location invalide dans la base ──

    @Test
    fun `5 — reject invalid base location (y too high)`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.setIn(Location::y, 9999.0) { base.location }
        println("Base Y unchanged: ${store.data.base.location.y}")
    }

    // ── Collection validateEach : membre invalide ──

    @Test
    fun `6 — reject adding member with blank username`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.mutate(GuildData::members) { members ->
            (members as MutableList).add(GuildMember("", "member", 0))
        }
        assertEquals(3, store.data.members.size) // la mutation invalide a été restaurée (C-05)
        println("Members count: ${store.data.members.size}")
    }

    // ── Cross-field : leader doit être dans les membres ──

    @Test
    fun `7 — reject leader not in members`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.set(GuildData::leaderName, "UnknownPlayer")
        println("Leader unchanged: '${store.data.leaderName}'")
    }

    // ── Object-level : trop de membres ──

    @Test
    fun `8 — reject members exceeding maxMembers`() {
        val store = freshStore()
        store.set(GuildData::maxMembers, 3) // set max to exactly current count
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.mutate(GuildData::members) { members ->
            (members as MutableList).add(GuildMember("NewGuy", "member", 0))
        }
        println("Members count still: ${store.data.members.size}")
    }

    // ── Unicité dans une collection ──

    @Test
    fun `9 — reject duplicate member usernames`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.mutate(GuildData::members) { members ->
            (members as MutableList).add(GuildMember("Alex_99", "member", 0)) // already exists
        }
        println("Members: ${store.data.members.map { it.username }}")
    }

    // ── Map : permissions incohérentes ──

    @Test
    fun `10 — reject rank with canKick but not canInvite`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.mutate(GuildData::ranks) { ranks ->
            (ranks as MutableMap)["officer"] = RankPermissions(canInvite = false, canKick = true, canBuild = true)
        }
        println("Officer perms unchanged: ${store.data.ranks["officer"]}")
    }

    // ── Cross-field Map/Collection : rôle d'un membre sans rang défini ──

    @Test
    fun `11 — reject member role not defined in ranks`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.mutate(GuildData::members) { members ->
            (members as MutableList).add(GuildMember("Hacker42", "admin", 0)) // 'admin' rank doesn't exist
        }
        println("Members: ${store.data.members.map { "${it.username}(${it.role})" }}")
    }

    // ── Nested collection dans un nested object (upgrades dans base) ──

    @Test
    fun `12 — reject upgrade level exceeding maxLevel`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("BLOCKED: ${op.validationError}")
        }
        store.mutateIn(GuildBase::upgrades, { base }) { upgrades ->
            (upgrades as MutableList).add(GuildUpgrade("Caserne", 10, 3)) // level 10 > maxLevel 3
        }
        println("Upgrades: ${store.data.base.upgrades.map { "${it.name} Lv${it.level}/${it.maxLevel}" }}")
    }

    // ── Transaction : tout ou rien ──

    @Test
    fun `13 — transaction rollback on invalid state`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is TransactionOperation<*>)
                if (!op.success) println("TRANSACTION ROLLED BACK: ${op.validationError}")
                else println("TRANSACTION OK")
        }
        store.transaction {
            name = ""          // invalide
            level = 999        // invalide
            tag = "oops"       // invalide
        }
        assertEquals("Les Conquérants", store.data.name) // la transaction invalide a tout restauré (C-05)
        assertEquals(1, store.data.level)
        println("Guild still valid: name='${store.data.name}', level=${store.data.level}, tag='${store.data.tag}'")
    }

    // ── Transaction valide ──

    @Test
    fun `14 — transaction success with multiple valid changes`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is TransactionOperation<*>)
                println("TRANSACTION ${if (op.success) "OK" else "FAILED"}")
        }
        store.transaction {
            name = "Les Immortels"
            tag = "IMT"
            level = 5
            motd = "Nouvelle ère !"
        }
        println("Updated: name='${store.data.name}', tag='${store.data.tag}', level=${store.data.level}")
    }
}
