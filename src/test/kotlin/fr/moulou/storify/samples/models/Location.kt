package fr.moulou.storify.samples.models

import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable

@Serializable
data class Location(
    val x : Double,
    val y : Double,
    val z : Double,
    val yaw : Float,
    val pitch : Float,
    val dimension : String
)

// ─────────────────────────────────────────────
// Validator pour Location
// ─────────────────────────────────────────────
class LocationValidator : Validator<Location> {
    override fun validate(data: Location, ctx: ValidationContext) {
        // Y doit être entre -64 et 320 (limites Minecraft)
        ctx.check(data.y in -64.0..320.0, "y", "must be between -64 and 320", data.y)

        // Pitch entre -90 et 90
        ctx.check(data.pitch in -90f..90f, "pitch", "must be between -90 and 90", data.pitch)

        // Yaw entre -180 et 180
        ctx.check(data.yaw in -180f..180f, "yaw", "must be between -180 and 180", data.yaw)

        // Dimension ne doit pas être vide et doit suivre le format namespace:path
        ctx.check(data.dimension.isNotBlank(), "dimension", "must not be blank", data.dimension)
        ctx.check(
            data.dimension.matches(Regex("^[a-z_][a-z0-9_]*:[a-z_][a-z0-9_/]*$")),
            "dimension", "must follow 'namespace:path' format (e.g. 'minecraft:overworld')", data.dimension
        )
    }
}

class LocationValidator2 : Validator<Location> {
    override fun validate(data: Location, ctx: ValidationContext) {
        // Y doit être entre -64 et 320 (limites Minecraft)
        ctx.check(data.y in -64.0..320.0, "y", "must be between -64 and 320", data.y)

        // Pitch entre -90 et 90
        ctx.check(data.pitch in -90f..90f, "pitch", "must be between -90 and 90", data.pitch)

        // Yaw entre -180 et 180
        ctx.check(data.yaw in -180f..180f, "yaw", "must be between -180 and 180", data.yaw)

        // Dimension ne doit pas être vide et doit suivre le format namespace:path
        ctx.check(data.dimension.isNotBlank(), "dimension", "must not be blank", data.dimension)
        ctx.check(
            data.dimension.matches(Regex("^[a-z_][a-z0-9_]*:[a-z_][a-z0-9_/]*$")),
            "dimension", "must follow 'namespace:path' format (e.g. 'minecraft:overworld')", data.dimension
        )
    }
}