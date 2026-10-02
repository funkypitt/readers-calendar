package com.freedomfighter.readerscalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "1 hour before", not "1 hours before": the three reminder wordings are plurals in every
 * language of the app, each with the forms its language needs (Russian: one, few, many).
 */
class ReminderWordsTest {
    private val names = listOf("reminder_minutes", "reminder_hours", "reminder_days")
    private val needed = mapOf(
        "values" to setOf("one", "other"), "values-fr" to setOf("one", "other"), "values-de" to setOf("one", "other"),
        "values-es" to setOf("one", "other"), "values-pt" to setOf("one", "other"), "values-ru" to setOf("one", "few", "many", "other")
    )

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** name → (quantity → words) of one strings.xml. */
    private fun plurals(dir: String): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res(), "$dir/strings.xml"))
        val out = HashMap<String, Map<String, String>>()
        val nodes = doc.getElementsByTagName("plurals")
        for (i in 0 until nodes.length) {
            val p = nodes.item(i) as org.w3c.dom.Element
            val items = p.getElementsByTagName("item")
            out[p.getAttribute("name")] = (0 until items.length).associate { (items.item(it) as org.w3c.dom.Element).let { e -> e.getAttribute("quantity") to e.textContent } }
        }
        return out
    }

    @Test fun everyLanguageOfTheAppIsCovered() {
        val dirs = res().listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").isFile }!!.map { it.name }.toSet()
        assertEquals(needed.keys, dirs)
    }

    @Test fun everyLanguageHasTheFormsItNeeds() {
        for ((dir, quantities) in needed) {
            val all = plurals(dir)
            for (name in names) {
                val forms = all[name] ?: throw AssertionError("$dir: $name is not a plural")
                assertEquals("$dir $name", quantities, forms.keys)
                for ((q, words) in forms) assertTrue("$dir $name $q", "%1\$d" in words)
            }
        }
    }

    @Test fun oneIsNotWrittenLikeSeveral() {
        for (dir in needed.keys) for (name in names) {
            val forms = plurals(dir).getValue(name)
            assertNotEquals("$dir $name", forms["one"], forms.getValue(if ("many" in forms) "many" else "other"))
        }
    }

    @Test fun noPlainStringIsLeftBehind() {
        for (dir in needed.keys) {
            val text = File(res(), "$dir/strings.xml").readText()
            for (name in names) assertTrue("$dir $name", "<string name=\"$name\"" !in text)
        }
    }
}
