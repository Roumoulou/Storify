package fr.moulou.storify.support

import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable

@Serializable
data class Home(
    var name: String,
    var location: Location
)

// ─────────────────────────────────────────────
// Validator pour Home
// ─────────────────────────────────────────────
class HomeValidator : Validator<Home> {
    private val locationValidator = LocationValidator()

    override fun validate(data: Home, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.check(data.name.length <= 32, "name", "must be 32 characters or less", data.name)
        ctx.check(
            data.name.matches(Regex("^[a-zA-Z0-9_-]+$")),
            "name", "must contain only alphanumeric characters, dashes or underscores", data.name
        )

        ctx.validateNested("location", data.location, locationValidator)
    }
}

class HomeValidator2 : Validator<Home> {
    private val locationValidator2 = LocationValidator2()

    override fun validate(data: Home, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        ctx.check(data.name.length <= 32, "name", "must be 32 characters or less", data.name)
        ctx.check(
            data.name.matches(Regex("^[a-zA-Z0-9_-]+$")),
            "name", "must contain only alphanumeric characters, dashes or underscores", data.name
        )

        ctx.validateNested("location", data.location, locationValidator2)
    }
}
