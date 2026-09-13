@file:Suppress("unused")

package fr.moulou.storify

import fr.moulou.storify.utils.DateUtils.formatLocal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class StoreMeta(
    @SerialName("createdAt")
    val createdAt: String = Clock.System.now().formatLocal(),

    @SerialName("lastModified")
    var lastModified: String = Clock.System.now().formatLocal(),

    @SerialName("version")
    var version: Int = 1,

    @SerialName("custom")
    var customData: MutableMap<String, String> = mutableMapOf()
) {
    fun touch() {
        lastModified = Clock.System.now().formatLocal()
    }

    fun setCustom(key: String, value: String) {
        customData[key] = value
    }

    fun getCustom(key: String): String? = customData[key]
}