package fr.moulou.storify.legacy

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// ──────────────────────────────────────────────────────────
// Data classes — propres, aucune logique de validation
// ──────────────────────────────────────────────────────────

@Serializable
data class HomeLocation(
    var world: String,
    var x: Double,
    var y: Double,
    var z: Double
)

@Serializable
data class PlayerStats(
    var health: Int,
    var level: Int,
    var xp: Int
)

@Serializable
@StorePath("C:\\temp\\player_data.json")
@StoreConfiguration(withAutoSave = false, withValidation = true, validateOnUpdate = true)
@StoreValidator(PlayerDataValidator::class)
data class PlayerData(
    var name: String,
    var home: HomeLocation,
    var stats: PlayerStats
) {
    companion object : Defaultable<PlayerData> {
        override fun getDefault() = PlayerData(
            name  = "Steve",
            home  = HomeLocation(world = "overworld", x = 0.0, y = 64.0, z = 0.0),
            stats = PlayerStats(health = 20, level = 1, xp = 0)
        )
    }
}

@Serializable
@StorePath("C:\\temp\\bad_player.json")
@StoreConfiguration(withAutoSave = false, withValidation = true)
@StoreValidator(BadPlayerDataValidator::class)
data class BadPlayerData(
    var name: String,
    var home: HomeLocation
) {
    companion object : Defaultable<BadPlayerData> {
        override fun getDefault() = BadPlayerData(
            name = "",
            home = HomeLocation(world = "", x = 0.0, y = 9999.0, z = 0.0)
        )
    }
}

// ──────────────────────────────────────────────────────────
// Validators — package dédié en production
// ──────────────────────────────────────────────────────────

class HomeLocationValidator : Validator<HomeLocation> {
    override fun validate(data: HomeLocation, ctx: ValidationContext) {
        ctx.check(data.world.isNotBlank(), "world", "must not be blank",         data.world)
        ctx.check(data.y in -64.0..320.0,  "y",     "must be between -64 and 320", data.y)
    }
}

class PlayerStatsValidator : Validator<PlayerStats> {
    override fun validate(data: PlayerStats, ctx: ValidationContext) {
        ctx.check(data.health in 1..20, "health", "must be between 1 and 20", data.health)
        ctx.check(data.level >= 1,      "level",  "must be at least 1",       data.level)
        ctx.check(data.xp >= 0,         "xp",     "must not be negative",     data.xp)
    }
}

class PlayerDataValidator : Validator<PlayerData> {
    override fun validate(data: PlayerData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.validateNested("home",  data.home,  HomeLocationValidator())
        ctx.validateNested("stats", data.stats, PlayerStatsValidator())
    }
}

class BadPlayerDataValidator : Validator<BadPlayerData> {
    override fun validate(data: BadPlayerData, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.validateNested("home", data.home, HomeLocationValidator())
    }
}

// ──────────────────────────────────────────────────────────
// Demo / tests
// ──────────────────────────────────────────────────────────

class ValidationDemo {

    private fun freshStore(): fr.moulou.storify.core.BaseStore<PlayerData> {
        java.io.File("C:\\temp\\player_data.json").delete()
        return StoreFactory.create<PlayerData>()
    }

    @Test
    fun `demo — valid store creation`() {
        val store = freshStore()
        println("Player loaded: ${store.data}")
    }

    @Test
    fun `demo — valid update (rename player)`() {
        val store = freshStore()
        store.set(PlayerData::name, "Alex")
        println("Renamed: ${store.data.name}")
    }

    @Test
    fun `demo — invalid update blocked (health too high)`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("Validation blocked ✗\n${op.validationError}")
            else
                println("Update accepted ✓ — health = ${store.data.stats.health}")
        }
        store.setIn(PlayerStats::health, 999) { this.stats }
        assertEquals(20, store.data.stats.health) // refusé pour de vrai depuis C-05 (validateOnUpdate)
    }

    @Test
    fun `demo — invalid update blocked (blank world name)`() {
        val store = freshStore()
        store.registerOnUpdate { op ->
            if (op is ValidationFailedOperation<*, *, *>)
                println("Validation blocked ✗\n${op.validationError}")
        }
        store.setIn(HomeLocation::world, "") { home }
    }

    @Test
    fun `demo — FEAT-4 invalid default throws clear error`() {
        java.io.File("C:\\temp\\bad_player.json").delete()
        try {
            StoreFactory.create<BadPlayerData>()
        } catch (e: IllegalStateException) {
            println("FEAT-4 caught ✗\n${e.message}")
        }
    }
}