package fr.moulou.storify.validation

class ValidationContext(
    private val currentPath: String = "",
    private val currentClassName: String = "",
    private val _errors: MutableList<ValidationError> = mutableListOf()
) {
    val errors: List<ValidationError> get() = _errors.toList()
    val hasErrors: Boolean get() = _errors.isNotEmpty()
    val errorCount: Int get() = _errors.size

    fun check(condition: Boolean, field: String, message: String, rejectedValue: Any? = null) {
        if (!condition) {
            _errors.add(ValidationError(path = currentPath, className = currentClassName, field = field, message = message, rejectedValue = rejectedValue))
        }
    }

    fun addError(field: String?, message: String, rejectedValue: Any? = null) {
        _errors.add(ValidationError(path = currentPath, className = currentClassName, field = field, message = message, rejectedValue = rejectedValue))
    }

    fun addObjectError(message: String) {
        _errors.add(ValidationError(path = currentPath, className = currentClassName, field = null, message = message))
    }

    fun <T : Any> validateNested(fieldName: String, data: T, validator: Validator<T>) {
        val nestedPath = if (currentPath.isEmpty()) fieldName else "$currentPath.$fieldName"
        val nestedCtx = ValidationContext(
            currentPath = nestedPath,
            currentClassName = data::class.simpleName ?: "Unknown",
            _errors = _errors
        )
        validator.validate(data, nestedCtx)
    }

    fun <T : Any> validateEach(fieldName: String, items: Iterable<T>, validator: Validator<T>) {
        items.forEachIndexed { index, item ->
            val indexedPath = if (currentPath.isEmpty()) "$fieldName[$index]" else "$currentPath.$fieldName[$index]"
            val nestedCtx = ValidationContext(
                currentPath = indexedPath,
                currentClassName = item::class.simpleName ?: "Unknown",
                _errors = _errors
            )
            validator.validate(item, nestedCtx)
        }
    }

    fun <T : Any> validateEach(fieldName: String, items: Array<T>, validator: Validator<T>) {
        validateEach(fieldName, items.toList(), validator)
    }

    fun formatErrors(): String {
        if (_errors.isEmpty()) return "Validation passed — no errors."
        return buildString {
            appendLine("Validation failed with ${_errors.size} error(s):")
            _errors.forEachIndexed { index, error ->
                appendLine("  ${index + 1}. ${error.formatFull()}")
            }
        }.trimEnd()
    }

    fun formatErrorsShort(): String {
        if (_errors.isEmpty()) return "OK"
        return _errors.joinToString("\n") { "  - ${it.formatShort()}" }
    }
}
