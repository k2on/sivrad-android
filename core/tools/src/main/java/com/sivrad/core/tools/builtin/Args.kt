package com.sivrad.core.tools.builtin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

// Accessors for arguments that have already passed ObjectSchema.validate.

internal fun JsonObject.long(name: String): Long = (this[name] as JsonPrimitive).long

internal fun JsonObject.string(name: String): String = (this[name] as JsonPrimitive).content

internal fun JsonObject.optString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.optStrings(name: String): List<String> =
    (this[name] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

internal fun JsonObject.optStringMap(name: String): Map<String, String> =
    (this[name] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" },
        s.takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ").ifEmpty { "0 s" }
}
