package com.sivrad.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The subset of JSON Schema the tools use. Small on purpose: every construct
 * here has to be expressible as a GBNF rule (core/llm) and checked by
 * [validate], and the two must agree.
 */
sealed interface Param {
    val description: String

    fun toJsonSchema(): JsonObject

    /** Appends a message to [errors] for every way [value] fails this schema. */
    fun validate(path: String, value: JsonElement, errors: MutableList<String>)
}

data class IntegerParam(
    override val description: String,
    val min: Long? = null,
    val max: Long? = null,
) : Param {
    override fun toJsonSchema() = buildJsonObject {
        put("type", "integer")
        put("description", description)
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
    }

    override fun validate(path: String, value: JsonElement, errors: MutableList<String>) {
        val n = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (n == null) {
            errors += "$path must be an integer"
            return
        }
        if (min != null && n < min) errors += "$path must be >= $min"
        if (max != null && n > max) errors += "$path must be <= $max"
    }
}

data class StringParam(
    override val description: String,
    val enum: List<String>? = null,
    val maxLength: Int? = null,
) : Param {
    override fun toJsonSchema() = buildJsonObject {
        put("type", "string")
        put("description", description)
        enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        maxLength?.let { put("maxLength", it) }
    }

    override fun validate(path: String, value: JsonElement, errors: MutableList<String>) {
        val s = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s == null) {
            errors += "$path must be a string"
            return
        }
        if (enum != null && s !in enum) errors += "$path must be one of ${enum.joinToString()}"
        if (maxLength != null && s.length > maxLength) errors += "$path is longer than $maxLength characters"
    }
}

data class ArrayParam(
    override val description: String,
    val items: StringParam,
    val maxItems: Int? = null,
) : Param {
    override fun toJsonSchema() = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items.toJsonSchema())
        maxItems?.let { put("maxItems", it) }
    }

    override fun validate(path: String, value: JsonElement, errors: MutableList<String>) {
        if (value !is JsonArray) {
            errors += "$path must be an array"
            return
        }
        if (maxItems != null && value.size > maxItems) errors += "$path has more than $maxItems items"
        value.forEachIndexed { i, e -> items.validate("$path[$i]", e, errors) }
    }
}

/** A JSON object whose keys are free-form and whose values are strings (HTTP headers). */
data class StringMapParam(
    override val description: String,
    val maxEntries: Int? = null,
) : Param {
    override fun toJsonSchema() = buildJsonObject {
        put("type", "object")
        put("description", description)
        putJsonObject("additionalProperties") { put("type", "string") }
    }

    override fun validate(path: String, value: JsonElement, errors: MutableList<String>) {
        if (value !is JsonObject) {
            errors += "$path must be an object"
            return
        }
        if (maxEntries != null && value.size > maxEntries) errors += "$path has more than $maxEntries entries"
        value.forEach { (k, v) ->
            if (v !is JsonPrimitive || !v.isString) errors += "$path.$k must be a string"
        }
    }
}

/** A tool's arguments: a closed object. Property order is the order the model is asked to emit them in. */
data class ObjectSchema(
    val properties: LinkedHashMap<String, Param>,
    val required: List<String>,
) {
    init {
        require(required.all { it in properties }) { "required names an unknown property" }
        // The grammar emits required properties first, then optional ones in
        // declaration order, and needs at least one required property to hang
        // the commas off.
        require(required.isNotEmpty()) { "every tool needs at least one required parameter" }
    }

    /** Required properties first, then optional, each group in declaration order. */
    val orderedProperties: List<Pair<String, Param>>
        get() = properties.entries
            .sortedBy { if (it.key in required) 0 else 1 }
            .map { it.key to it.value }

    fun toJsonSchema() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v.toJsonSchema()) } }
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    fun validate(value: JsonElement): List<String> {
        val errors = mutableListOf<String>()
        if (value !is JsonObject) return listOf("arguments must be a JSON object")
        for (name in required) {
            if (value[name] == null || value[name] is JsonNull) errors += "missing required argument '$name'"
        }
        for ((name, v) in value) {
            val param = properties[name]
            when {
                param == null -> errors += "unknown argument '$name'"
                v is JsonNull && name !in required -> Unit // explicit null == absent
                else -> param.validate(name, v, errors)
            }
        }
        return errors
    }

    companion object {
        fun of(vararg props: Pair<String, Param>, required: List<String>) =
            ObjectSchema(linkedMapOf(*props), required)
    }
}
