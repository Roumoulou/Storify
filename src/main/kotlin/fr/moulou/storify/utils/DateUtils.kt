package fr.moulou.storify.utils

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

object DateUtils {
    fun Instant.formatLocal(pattern: String = "yyyy-MM-dd HH:mm:ss:SSS"): String {
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return this.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime().format(formatter)
    }
}