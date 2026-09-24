package com.sivrad.core.llm

import com.sivrad.core.tools.ToolRegistry
import com.sivrad.core.tools.builtin.BuiltinTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolGrammarTest {
    private val registry = ToolRegistry(BuiltinTools.all())
    private val grammar = ToolGrammar.build(registry)

    @Test
    fun `grammar has a rule per tool and every referenced rule is defined`() {
        val defined = grammar.lines().filter { "::=" in it }.map { it.substringBefore("::=").trim() }.toSet()
        for (t in registry.tools) assertTrue(grammar, "call-${t.name.replace('_', '-')}" in defined)
        val body = grammar.lines().filter { "::=" in it }.joinToString("\n") {
            it.substringAfter("::=")
                .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), " ") // drop literals
                .replace(Regex("\\[(\\\\.|[^]\\\\])*]"), " ") // drop char classes
        }
        val referenced = Regex("[a-z][a-z0-9-]*").findAll(body).map { it.value }.toSet()
        assertEquals(emptySet<String>(), referenced - defined)
        // Written out so the grammar can be checked with llama.cpp's own
        // parser (see README, "Checking the grammar").
        File("build/tool-grammar.gbnf").apply { parentFile?.mkdirs() }.writeText(grammar)
    }

    @Test
    fun `tool call literals are escaped for GBNF`() {
        assertTrue(grammar.contains("""call-set-timer ::= "{\"name\": \"set_timer\", \"arguments\": " args-set-timer "}""""))
        assertTrue(grammar.contains("""tool-call ::= "<tool_call>\n" call "\n</tool_call>""""))
        assertEquals("\"a\\\"b\\\\c\\n\"", ToolGrammar.lit("a\"b\\c\n"))
    }

    @Test
    fun `optional arguments are optional, required ones are not`() {
        val timer = grammar.lines().single { it.startsWith("args-set-timer ::=") }
        assertTrue(timer, timer.contains("\"\\\"duration_seconds\\\"\" ws \":\" ws integer"))
        assertTrue(timer, timer.contains("(ws \",\" ws \"\\\"label\\\"\" ws \":\" ws string)?"))
        val alarm = grammar.lines().single { it.startsWith("args-set-alarm ::=") }
        assertTrue(alarm, alarm.contains("\"\\\"monday\\\"\" | "))
    }
}
