package com.sivrad.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameMatcherTest {
    private val contacts = listOf("John Smith", "Joanna Lee", "Aidan Byrne", "Katherine Wu", "Mom", "Siobhan Kelly", "Dad")

    private fun best(q: String) = NameMatcher.best(q, contacts, { it })?.item

    @Test
    fun `misheard names still find the contact`() {
        assertEquals("John Smith", best("Jon"))
        assertEquals("Aidan Byrne", best("Aiden"))
        assertEquals("Katherine Wu", best("Kathryn"))
        assertEquals("Mom", best("mum"))
        assertEquals("Joanna Lee", best("Johanna Lee"))
    }

    @Test
    fun `unrelated or ambiguous names do not guess`() {
        assertNull(best("Bartholomew"))
        assertNull(best("xyz"))
        // "Jo" is as close to John as to Joanna.
        assertNull(best("Jo"))
    }

    @Test
    fun `app labels`() {
        val apps = listOf("Signal", "Vanadium", "Camera", "Clock", "Contacts", "Messages", "Fennec")
        assertEquals("Vanadium", NameMatcher.best("vanadium browser", apps, { it })?.item ?: NameMatcher.best("vanadium", apps, { it })?.item)
        assertEquals("Fennec", NameMatcher.best("fenic", apps, { it })?.item)
        assertEquals("Messages", NameMatcher.best("message", apps, { it })?.item)
    }

    @Test
    fun `invented package names still find the app`() {
        val launchers = listOf(
            "Signal" to "org.thoughtcrime.securesms",
            "Vanadium" to "app.vanadium.browser",
            "Molly" to "im.molly.app",
            "Camera" to "app.grapheneos.camera",
        )
        fun pick(q: String) = com.sivrad.core.tools.builtin.OpenAppTool.pickLauncher(q, launchers)?.first
        assertEquals("Signal", pick("signalmobile.com.signal"))
        assertEquals("Signal", pick("Signal"))
        assertEquals("Vanadium", pick("com.vanadium"))
        assertEquals("Molly", pick("molly"))
        assertNull(pick("com.example.nothing"))
    }
}
