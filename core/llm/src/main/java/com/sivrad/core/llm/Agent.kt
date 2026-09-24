package com.sivrad.core.llm

import com.sivrad.core.tools.ToolExecutor
import com.sivrad.core.tools.ToolRegistry
import com.sivrad.core.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface AgentEvent {
    data object Thinking : AgentEvent

    /** The visible reply so far, while it streams. */
    data class Partial(val text: String) : AgentEvent
    data class ToolStarted(val name: String) : AgentEvent
    data class ToolFinished(val name: String?, val result: ToolResult) : AgentEvent
    data class Reply(val text: String) : AgentEvent
}

/**
 * The tool-calling loop for one overlay session. [history] lives exactly as
 * long as this object; the session drops it when the overlay closes.
 */
class Agent(
    private val engine: LlmEngine,
    registry: ToolRegistry,
    private val maxToolRounds: Int = 3,
) {
    private val prompts = PromptBuilder(registry)
    private val grammar = ToolGrammar.build(registry)
    private val history = mutableListOf<ChatMessage>()

    /** Evaluates the system prompt so the first real turn only pays for the user's words. */
    suspend fun warmUp() {
        engine.prefill(engine.render(prompts.messages(emptyList()), addAssistant = false))
    }

    /**
     * Runs one user turn: generate; if the model calls a tool, execute it,
     * feed the result back and generate again, up to [maxToolRounds] times.
     */
    fun respond(userText: String, executor: ToolExecutor): Flow<AgentEvent> = flow {
        val mark = history.size
        history += ChatMessage.user("$userText\n\n(${now()})")
        try {
            repeat(maxToolRounds + 1) { round ->
                emit(AgentEvent.Thinking)
                val prompt = engine.render(prompts.messages(history))
                val out = StringBuilder()
                // No tool calls on the last round: the model has to answer.
                val g = if (round < maxToolRounds) grammar else null
                engine.generate(prompt, g).collect { piece ->
                    out.append(piece)
                    ModelOutput.displayText(out.toString()).takeIf { it.isNotEmpty() }
                        ?.let { emit(AgentEvent.Partial(it)) }
                }
                when (val parsed = ModelOutput.parse(out.toString())) {
                    is ModelOutput.Text -> {
                        val text = parsed.text.ifEmpty { "Sorry, I have no answer to that." }
                        history += ChatMessage.assistant(out.toString())
                        emit(AgentEvent.Reply(text))
                        return@flow
                    }
                    is ModelOutput.ToolCall -> {
                        history += ChatMessage.assistant(out.toString())
                        emit(AgentEvent.ToolStarted(toolName(parsed.json)))
                        val (name, result) = executor.execute(parsed.json)
                        emit(AgentEvent.ToolFinished(name, result))
                        history += ChatMessage.toolResponse(PromptBuilder.pythonJson(result.toModelJson()))
                    }
                }
            }
            emit(AgentEvent.Reply("I couldn't finish that; too many steps."))
        } catch (e: Throwable) {
            // A turn that did not complete leaves no trace, so retrying it
            // starts from the same history.
            while (history.size > mark) history.removeAt(history.lastIndex)
            throw e
        }
    }

    /** Drops the last completed turn (for "retry") and returns its user text. */
    fun popLastTurn(): String? {
        val i = history.indexOfLast { it.role == Role.User && !it.content.startsWith("<tool_response>") }
        if (i < 0) return null
        val text = history[i].content.substringBeforeLast("\n\n(")
        while (history.size > i) history.removeAt(history.lastIndex)
        return text
    }

    fun clear() = history.clear()

    private fun toolName(json: String) =
        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "tool"

    private fun now() =
        "current time: " + SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
