package fr.moulou.storify.samples.models

import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable

@Serializable
data class Rule(
    var homesEnabled: Boolean,
    var maxHomes: Int,
    var cooldown: Int,
    var teleportDelay: Int,
    var cancelOnMove: Boolean,

    var allowedDimensionsFromWhichPlayerCanCreateAHome : MutableSet<String>
)

// ─────────────────────────────────────────────
// Validator pour Rule
// ─────────────────────────────────────────────
class RuleValidator : Validator<Rule> {
    override fun validate(data: Rule, ctx: ValidationContext) {
        ctx.check(data.maxHomes in 1..100, "maxHomes", "must be between 1 and 100", data.maxHomes)
        ctx.check(data.cooldown >= 0, "cooldown", "must be non-negative", data.cooldown)
        ctx.check(data.teleportDelay >= 0, "teleportDelay", "must be non-negative", data.teleportDelay)
        ctx.check(
            data.teleportDelay <= data.cooldown,
            "teleportDelay", "must not exceed cooldown (${data.cooldown}s)", data.teleportDelay
        )

        // Les dimensions autorisées ne doivent pas être vides si les homes sont activés
        if (data.homesEnabled) {
            ctx.check(
                data.allowedDimensionsFromWhichPlayerCanCreateAHome.isNotEmpty(),
                "allowedDimensionsFromWhichPlayerCanCreateAHome",
                "must contain at least one dimension when homes are enabled"
            )
        }

        // Chaque dimension doit suivre le format namespace:path
        data.allowedDimensionsFromWhichPlayerCanCreateAHome.forEachIndexed { index, dim ->
            ctx.check(
                dim.matches(Regex("^[a-z_][a-z0-9_]*:[a-z_][a-z0-9_/]*$")),
                "allowedDimensionsFromWhichPlayerCanCreateAHome[$index]",
                "invalid dimension format", dim
            )
        }
    }
}

class RuleValidator2 : Validator<Rule> {
    override fun validate(data: Rule, ctx: ValidationContext) {
        ctx.check(data.maxHomes in 1..100, "maxHomes", "must be between 1 and 100", data.maxHomes)
        ctx.check(data.cooldown >= 0, "cooldown", "must be non-negative", data.cooldown)
        ctx.check(data.teleportDelay >= 0, "teleportDelay", "must be non-negative", data.teleportDelay)
        ctx.check(
            data.teleportDelay <= data.cooldown,
            "teleportDelay", "must not exceed cooldown (${data.cooldown}s)", data.teleportDelay
        )

        // Les dimensions autorisées ne doivent pas être vides si les homes sont activés
        if (data.homesEnabled) {
            ctx.check(
                data.allowedDimensionsFromWhichPlayerCanCreateAHome.isNotEmpty(),
                "allowedDimensionsFromWhichPlayerCanCreateAHome",
                "must contain at least one dimension when homes are enabled"
            )
        }

        // Chaque dimension doit suivre le format namespace:path
        data.allowedDimensionsFromWhichPlayerCanCreateAHome.forEachIndexed { index, dim ->
            ctx.check(
                dim.matches(Regex("^[a-z_][a-z0-9_]*:[a-z_][a-z0-9_/]*$")),
                "allowedDimensionsFromWhichPlayerCanCreateAHome[$index]",
                "invalid dimension format", dim
            )
        }
    }
}