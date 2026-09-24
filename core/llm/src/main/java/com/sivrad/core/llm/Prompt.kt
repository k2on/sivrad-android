package com.sivrad.core.llm

import com.sivrad.core.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Role(val wire: String) { System("system"), User("user"), Assistant("assistant") }

data class ChatMessage(val role: Role, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage(Role.System, text)
        fun user(text: String) = ChatMessage(Role.User, text)
        fun assistant(text: String) = ChatMessage(Role.Assistant, text)

        /**
         * Qwen3's template has no `tool` role of its own: it renders tool
         * results as a user turn wrapped in `<tool_response>`. Doing the same
         * here keeps the prompt identical to what the model was trained on
         * while letting llama.cpp's built-in template renderer (which does not
         * run Jinja) handle the role wrapping.
         */
        fun toolResponse(json: String) = ChatMessage(Role.User, "<tool_response>\n$json\n</tool_response>")
    }
}

/**
 * Builds the system prompt, with the tool definitions laid out exactly as
 * Qwen3's chat template does when `tools` is passed (the Hermes format).
 */
class PromptBuilder(private val registry: ToolRegistry) {

    val systemPrompt: String by lazy {
        buildString {
            append(INSTRUCTIONS)
            append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
            append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
            for (def in registry.definitions()) {
                append('\n')
                append(pythonJson(def))
            }
            append("\n</tools>\n\n")
            append("For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n")
            append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
        }
    }

    fun messages(history: List<ChatMessage>): List<ChatMessage> = listOf(ChatMessage.system(systemPrompt)) + history

    companion object {
        val INSTRUCTIONS = """
            You are Sivrad, a voice assistant running entirely on the user's phone, offline.
            The user's words come from speech recognition and may contain recognition errors; interpret them sensibly.
            Replies are shown as text in a small overlay: answer in one or two short plain sentences, with no markdown, lists or emoji.
            When the user asks for something one of the tools can do, call the tool instead of describing it. Otherwise answer directly.
            Only use contact names, URLs and apps the user actually mentioned. If something required is missing, ask for it briefly.
            After a tool runs you receive its result: tell the user in a few words what happened, or why it failed.
        """.trimIndent()

        /**
         * JSON with Python's `json.dumps` separators (", " and ": "), which is
         * what the `tojson` filter in Qwen's Jinja template produces.
         */
        fun pythonJson(e: JsonElement): String = when (e) {
            is JsonObject -> e.entries.joinToString(", ", "{", "}") { (k, v) -> "${JsonPrimitive(k)}: ${pythonJson(v)}" }
            is JsonArray -> e.joinToString(", ", "[", "]") { pythonJson(it) }
            is JsonPrimitive -> e.toString()
            JsonNull -> "null"
        }
    }
}
