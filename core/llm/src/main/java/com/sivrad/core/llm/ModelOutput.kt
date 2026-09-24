package com.sivrad.core.llm

sealed interface ModelOutput {
    data class Text(val text: String) : ModelOutput

    /** [json] is the call payload; [raw] is the whole completion, kept verbatim for history. */
    data class ToolCall(val json: String, val raw: String) : ModelOutput

    companion object {
        private const val OPEN = "<tool_call>"
        private const val CLOSE = "</tool_call>"
        private val THINK = Regex("<think>[\\s\\S]*?(</think>|$)")

        fun parse(completion: String): ModelOutput {
            // Qwen3-Instruct-2507 does not think, but a configured thinking
            // model might; its reasoning is never shown or parsed.
            val visible = completion.replace(THINK, "")
            val start = visible.indexOf(OPEN)
            if (start < 0) return Text(visible.trim())
            val end = visible.indexOf(CLOSE, start).let { if (it < 0) visible.length else it }
            return ToolCall(visible.substring(start + OPEN.length, end).trim(), completion)
        }

        /** What to show while [partial] is still streaming. */
        fun displayText(partial: String): String {
            val visible = partial.replace(THINK, "")
            val i = visible.indexOf('<')
            return when {
                visible.startsWith("<") -> ""
                i >= 0 && OPEN.startsWith(visible.substring(i).take(OPEN.length)) -> visible.substring(0, i)
                else -> visible
            }.trim()
        }
    }
}
