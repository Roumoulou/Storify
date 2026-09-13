package fr.moulou.storify.validation

sealed class ValidationResult {
    data object Success : ValidationResult()

    data class Failure(val errors: List<ValidationError>) : ValidationResult() {
        val errorCount: Int get() = errors.size

        fun formatFull(): String {
            return buildString {
                appendLine("Validation failed with ${errors.size} error(s):")
                errors.forEachIndexed { index, error ->
                    appendLine("  ${index + 1}. ${error.formatFull()}")
                }
            }.trimEnd()
        }

        fun formatShort(): String = errors.joinToString("\n") { "  - ${it.formatShort()}" }
    }

    val isValid: Boolean get() = this is Success
    val isInvalid: Boolean get() = this is Failure

    fun throwIfInvalid() {
        if (this is Failure) throw ValidationException(errors)
    }

    inline fun onFailure(block: (Failure) -> Unit): ValidationResult {
        if (this is Failure) block(this)
        return this
    }

    inline fun onSuccess(block: () -> Unit): ValidationResult {
        if (this is Success) block()
        return this
    }
}
