package com.sivrad.core.llm

import com.sivrad.core.tools.ToolRegistry
import com.sivrad.core.tools.builtin.BuiltinTools
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptTest {
    @Test
    fun `system prompt follows Qwen3's tool layout`() {
        val p = PromptBuilder(ToolRegistry(BuiltinTools.all())).systemPrompt
        assertTrue(p.contains("# Tools\n\nYou may call one or more functions to assist with the user query."))
        assertTrue(p.contains("<tools>\n{\"type\": \"function\", \"function\": {\"name\": \"set_timer\""))
        assertTrue(p.endsWith("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>"))
        File("build/system-prompt.txt").apply { parentFile?.mkdirs() }.writeText(p)
    }

    @Test
    fun `python-style json matches json dumps separators`() {
        val e = Json.parseToJsonElement("""{"a":[1,"x",{"b":null}],"c":true}""")
        assertEquals("""{"a": [1, "x", {"b": null}], "c": true}""", PromptBuilder.pythonJson(e))
    }

    @Test
    fun `completions parse into text or tool calls`() {
        assertEquals(ModelOutput.Text("Hello there."), ModelOutput.parse("Hello there.\n"))
        val call = ModelOutput.parse("<tool_call>\n{\"name\": \"set_timer\", \"arguments\": {\"duration_seconds\": 60}}\n</tool_call>")
        assertTrue(call is ModelOutput.ToolCall)
        assertEquals("{\"name\": \"set_timer\", \"arguments\": {\"duration_seconds\": 60}}", (call as ModelOutput.ToolCall).json)
        assertEquals(ModelOutput.Text("Hi."), ModelOutput.parse("<think>hmm</think>\n\nHi."))
    }

    @Test
    fun `streaming display hides tool-call markup`() {
        assertEquals("", ModelOutput.displayText("<tool_ca"))
        assertEquals("Sure", ModelOutput.displayText("Sure <tool"))
        assertEquals("It is 5 < 6", ModelOutput.displayText("It is 5 < 6"))
    }
}
