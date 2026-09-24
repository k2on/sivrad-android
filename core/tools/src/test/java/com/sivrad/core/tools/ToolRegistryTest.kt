package com.sivrad.core.tools

import com.sivrad.core.tools.builtin.BuiltinTools
import com.sivrad.core.tools.builtin.HttpRequestTool
import com.sivrad.core.tools.builtin.SendSmsTool
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolRegistry(BuiltinTools.all())

    private fun invalid(json: String): String {
        val r = registry.parse(json)
        assertTrue("expected invalid: $json", r is ParsedCall.Invalid)
        return (r as ParsedCall.Invalid).error
    }

    @Test
    fun `valid calls parse`() {
        val r = registry.parse("""{"name": "set_timer", "arguments": {"duration_seconds": 300, "label": "pasta"}}""")
        assertTrue(r is ParsedCall.Valid)
        assertEquals("set_timer", (r as ParsedCall.Valid).tool.name)

        assertTrue(registry.parse("""{"name":"set_alarm","arguments":{"hour":7,"minute":30,"days":["monday","friday"]}}""") is ParsedCall.Valid)
        assertTrue(registry.parse("""{"name":"http_request","arguments":{"method":"GET","url":"https://x.lan/a","headers":{"Accept":"text/plain"}}}""") is ParsedCall.Valid)
    }

    @Test
    fun `garbage is an error for the model, not an exception`() {
        assertTrue(invalid("not json").contains("not a JSON object"))
        assertTrue(invalid("""{"arguments": {}}""").contains("no \"name\""))
        assertTrue(invalid("""{"name": "format_disk", "arguments": {}}""").contains("unknown tool"))
    }

    @Test
    fun `schema violations are reported`() {
        assertTrue(invalid("""{"name":"set_timer","arguments":{}}""").contains("missing required argument 'duration_seconds'"))
        assertTrue(invalid("""{"name":"set_timer","arguments":{"duration_seconds":"5"}}""").contains("must be an integer"))
        assertTrue(invalid("""{"name":"set_timer","arguments":{"duration_seconds":0}}""").contains(">= 1"))
        assertTrue(invalid("""{"name":"set_timer","arguments":{"duration_seconds":1.5}}""").contains("must be an integer"))
        assertTrue(invalid("""{"name":"set_alarm","arguments":{"hour":25,"minute":0}}""").contains("<= 23"))
        assertTrue(invalid("""{"name":"set_alarm","arguments":{"hour":7,"minute":0,"days":["someday"]}}""").contains("must be one of"))
        assertTrue(invalid("""{"name":"set_timer","arguments":{"duration_seconds":5,"volume":11}}""").contains("unknown argument 'volume'"))
        assertTrue(invalid("""{"name":"http_request","arguments":{"method":"BREW","url":"https://x"}}""").contains("must be one of"))
        assertTrue(invalid("""{"name":"http_request","arguments":{"method":"GET","url":"https://x","headers":{"a":1}}}""").contains("headers.a must be a string"))
    }

    @Test
    fun `tool definitions carry the schemas`() {
        val defs = registry.definitions()
        assertEquals(5, defs.size)
        val timer = defs.first().toString()
        assertTrue(timer.contains("\"name\":\"set_timer\""))
        assertTrue(timer.contains("\"required\":[\"duration_seconds\"]"))
    }

    @Test
    fun `http allowlist matches on segment boundaries`() {
        val base = "https://ha.lan:8123/api"
        assertTrue(HttpRequestTool.isAllowed("https://ha.lan:8123/api".toHttpUrl(), base))
        assertTrue(HttpRequestTool.isAllowed("https://ha.lan:8123/api/states?x=1".toHttpUrl(), base))
        assertFalse(HttpRequestTool.isAllowed("https://ha.lan:8123/apiary".toHttpUrl(), base))
        assertFalse(HttpRequestTool.isAllowed("https://ha.lan/api/states".toHttpUrl(), base))
        assertFalse(HttpRequestTool.isAllowed("http://ha.lan:8123/api/states".toHttpUrl(), base))
        assertFalse(HttpRequestTool.isAllowed("https://ha.lan:8123/api/../admin".toHttpUrl(), base))
        assertTrue(HttpRequestTool.isAllowed("https://x.lan/anything".toHttpUrl(), "https://x.lan/"))
    }

    @Test
    fun `contact resolution prefers exact names and refuses to guess`() {
        fun row(id: Long, name: String, number: String, type: Int = 2, primary: Boolean = false) =
            SendSmsTool.Companion.Row(id, name, number, type, primary)

        assertEquals(SendSmsTool.Resolution.NotFound, SendSmsTool.pick("Ann", emptyList()))

        val single = SendSmsTool.pick("ann", listOf(row(1, "Ann Lee", "111", type = 1), row(1, "Ann Lee", "222", type = 2)))
        assertEquals(SendSmsTool.Contact("Ann Lee", "222"), (single as SendSmsTool.Resolution.Found).contact)

        val exact = SendSmsTool.pick("Ann", listOf(row(1, "Ann", "111"), row(2, "Annabel", "333")))
        assertEquals("111", (exact as SendSmsTool.Resolution.Found).contact.number)

        val ambiguous = SendSmsTool.pick("An", listOf(row(1, "Ann", "111"), row(2, "Andy", "333")))
        assertTrue(ambiguous is SendSmsTool.Resolution.Ambiguous)
    }
}
