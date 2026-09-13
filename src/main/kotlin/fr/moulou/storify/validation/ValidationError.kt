package fr.moulou.storify.validation

data class ValidationError(
    val path: String,
    val className: String,
    val field: String? = null,
    val message: String,
    val rejectedValue: Any? = null,
    val jsonLine: Int? = null
) {
    fun formatFull(): String {
        val fullPath = when {
            field != null && path.isNotEmpty() -> "$path.$field"
            field != null                      -> field
            else                               -> path
        }
        val location = "[$fullPath]"
        val rejected = if (rejectedValue != null) " (was: ${formatValue(rejectedValue)})" else ""
        val fieldPrefix = if (field != null) "$field: " else ""
        val lineInfo = if (jsonLine != null) " → line $jsonLine" else ""
        return "$location $fieldPrefix$message$rejected$lineInfo"
    }

    fun formatShort(): String {
        val fullPath = when {
            field != null && path.isNotEmpty() -> "$path.$field"
            field != null                      -> field
            else                               -> path
        }
        return "$fullPath: $message"
    }

    private fun formatValue(value: Any?): String = when (value) {
        is String -> "\"$value\""
        is Char -> "'$value'"
        is Collection<*> -> "Collection(size=${value.size})"
        is Map<*, *> -> "Map(size=${value.size})"
        is Array<*> -> "Array(size=${value.size})"
        null -> "null"
        else -> value.toString()
    }
}

