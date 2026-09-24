package com.sivrad.core.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolRegistry(tools: List<Tool>) {
    val tools: List<Tool> = tools.toList()
    private val byName = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool names" }
        require(tools.all { NAME.matches(it.name) }) { "tool names must match $NAME" }
    }

    operator fun get(name: String): Tool? = byName[name]

    /** OpenAI-style function definitions, the shape Qwen's chat template puts in `<tools>`. */
    fun definitions(): List<JsonObject> = tools.map { tool ->
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters.toJsonSchema())
            })
        }
    }

    /**
     * Parses and validates a call of the form `{"name": ..., "arguments": {...}}`.
     * Never throws: anything wrong comes back as [ParsedCall.Invalid] with a
     * message meant for the model.
     */
    fun parse(json: String): ParsedCall {
        val obj = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return ParsedCall.Invalid(null, "tool call is not a JSON object")

        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ParsedCall.Invalid(null, "tool call has no \"name\"")
        val tool = byName[name]
            ?: return ParsedCall.Invalid(name, "unknown tool '$name'; available: ${byName.keys.joinToString()}")
        val args = obj["arguments"] ?: JsonObject(emptyMap())
        val errors = tool.parameters.validate(args)
        if (errors.isNotEmpty()) return ParsedCall.Invalid(name, "invalid arguments for $name: ${errors.joinToString("; ")}")
        return ParsedCall.Valid(tool, args as JsonObject)
    }

    companion object {
        val NAME = Regex("[a-z][a-z0-9_]*")
    }
}

sealed interface ParsedCall {
    data class Valid(val tool: Tool, val args: JsonObject) : ParsedCall
    data class Invalid(val name: String?, val error: String) : ParsedCall
}
