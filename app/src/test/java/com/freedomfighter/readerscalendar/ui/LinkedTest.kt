package com.freedomfighter.readerscalendar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedTest {
    private fun uris(text: String) = findLinks(text).map { it.uri }
    private fun texts(text: String) = findLinks(text).map { text.substring(it.start, it.end) }

    @Test fun `a swiss number is dialled`() {
        assertEquals(listOf("tel:+41216541234"), uris("appeler le +41 21 654 12 34 avant midi"))
        assertEquals(listOf("+41 21 654 12 34"), texts("appeler le +41 21 654 12 34 avant midi"))
    }

    @Test fun `local formats`() {
        assertEquals(listOf("tel:021/6541234"), uris("021/654 12 34".let { "021/654 12 34" }).map { it.replace(" ", "") })
        assertEquals(listOf("tel:0216541234"), uris("021 654 12 34"))
        assertEquals(listOf("tel:(021)6541234"), uris("(021) 654 12 34"))
    }

    @Test fun `dates prices and room numbers are not numbers to call`() {
        assertEquals(emptyList<String>(), uris("rendez-vous le 17.09.2026 à 16:00, salle 12, 45 francs"))
        assertEquals(emptyList<String>(), uris("2026"))
        assertEquals(emptyList<String>(), uris("de 10 à 12"))
    }

    @Test fun `too long is not a number`() {
        assertEquals(emptyList<String>(), uris("réf 1234567890123456789"))
    }

    @Test fun `mail and web`() {
        assertEquals(listOf("mailto:anna@example.ch", "https://example.ch/agenda"),
            uris("écrire à anna@example.ch ou voir https://example.ch/agenda"))
        assertEquals(listOf("https://www.gallaz.ch"), uris("voir www.gallaz.ch."))
        assertEquals(listOf("www.gallaz.ch"), texts("voir www.gallaz.ch."))
    }

    @Test fun `a mail address is not read as a phone number`() {
        assertEquals(listOf("mailto:2026reunion@example.ch"), uris("2026reunion@example.ch"))
    }

    @Test fun `a sentence without anything to open`() {
        assertEquals(emptyList<String>(), uris("apporter le livre et les notes de la retraite"))
    }

    @Test fun `several links keep their order and text between them`() {
        val text = "tel +41 21 654 12 34, mail anna@example.ch, site https://example.ch"
        assertEquals(listOf("tel:+41216541234", "mailto:anna@example.ch", "https://example.ch"), uris(text))
        assertTrue(findLinks(text).zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test fun `a place becomes a map query`() {
        assertEquals("geo:0,0?q=Rue%20du%20Lac%2012%2C%20Lausanne", placeUri("Rue du Lac 12, Lausanne"))
    }
}
