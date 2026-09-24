package com.sivrad.core.llm

import com.sivrad.core.tools.ArrayParam
import com.sivrad.core.tools.IntegerParam
import com.sivrad.core.tools.ObjectSchema
import com.sivrad.core.tools.Param
import com.sivrad.core.tools.StringMapParam
import com.sivrad.core.tools.StringParam
import com.sivrad.core.tools.Tool
import com.sivrad.core.tools.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generates the GBNF grammar the model samples under.
 *
 * The model may either answer in plain text, or emit exactly one
 * `<tool_call>` whose JSON is forced to be a call to a registered tool with
 * arguments matching that tool's schema: correct names, required properties
 * present, types and enums honoured, no unknown keys. What a grammar cannot
 * cheaply express (numeric ranges, string lengths) is left to
 * [ObjectSchema.validate], which runs on every call regardless.
 *
 * A reply that starts with anything other than `<` is free text. One that
 * starts with `<` must be a tool call.
 */
object ToolGrammar {

    fun build(registry: ToolRegistry): String = buildString {
        val tools = registry.tools
        appendLine("root ::= tool-call | text")
        appendLine("text ::= [^<] [^\\x00]*")
        appendLine("tool-call ::= ${lit("<tool_call>\n")} call ${lit("\n</tool_call>")}")
        appendLine("call ::= " + tools.joinToString(" | ") { "call-${rule(it.name)}" })
        for (tool in tools) appendTool(tool)
        appendLine("""ws ::= [ ]?""")
        appendLine("""string ::= "\"" char* "\"" """.trimEnd())
        appendLine("""char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" hex hex hex hex)""")
        appendLine("""hex ::= [0-9a-fA-F]""")
        appendLine("""integer ::= "-"? ("0" | [1-9] [0-9]{0,17})""")
    }

    private fun StringBuilder.appendTool(tool: Tool) {
        val r = rule(tool.name)
        appendLine(
            "call-$r ::= " + lit("{\"name\": \"${tool.name}\", \"arguments\": ") + " args-$r " + lit("}"),
        )
        appendLine("args-$r ::= " + objectExpr(tool.parameters))
    }

    private fun objectExpr(schema: ObjectSchema): String {
        val parts = mutableListOf(lit("{"), "ws")
        schema.orderedProperties.forEachIndexed { i, (name, param) ->
            val kv = "${lit(JsonPrimitive(name).toString())} ws \":\" ws ${valueExpr(param)}"
            parts += when {
                i == 0 -> kv // the first property is always required (see ObjectSchema)
                name in schema.required -> "ws \",\" ws $kv"
                else -> "(ws \",\" ws $kv)?"
            }
        }
        parts += listOf("ws", lit("}"))
        return parts.joinToString(" ")
    }

    private fun valueExpr(p: Param): String = when (p) {
        is IntegerParam -> "integer"
        is StringParam -> p.enum?.let { values ->
            values.joinToString(" | ", "(", ")") { lit(JsonPrimitive(it).toString()) }
        } ?: "string"
        is ArrayParam -> {
            val item = valueExpr(p.items)
            "\"[\" ws ($item (ws \",\" ws $item)*)? ws \"]\""
        }
        is StringMapParam ->
            "\"{\" ws (string ws \":\" ws string (ws \",\" ws string ws \":\" ws string)*)? ws \"}\""
    }

    private fun rule(name: String) = name.replace('_', '-')

    /** A GBNF string literal. */
    internal fun lit(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
}
