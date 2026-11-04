package com.ars3nb.volta.core.locale

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every string Volta shows exists in every language it lists, with the same placeholders.
 * Strings live in one file per area (values/strings_<area>.xml, English) and the same file
 * in each values-xx folder. English is the default, so a gap would quietly show English;
 * this test makes a gap fail the build instead.
 */
class LocalizationTest {

    private val res = File("src/main/res")

    /** Files still on their way into all languages; empty once every area is done. */
    private val pending: Set<String> = emptySet()

    private val folders: List<String> = AppLanguage.SUPPORTED
        .filter { it.tag != "en" }
        .map { "values-" + it.tag.replace("-", "-r") }

    @Test
    fun everyAreaFileIsCompleteInEveryLanguage() {
        val problems = mutableListOf<String>()
        val files = File(res, "values").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }
            .orEmpty().filter { it.name !in pending }
        for (file in files) {
            val base = entries(file)
            for (folder in folders) {
                val other = File(res, "$folder/${file.name}")
                if (!other.isFile) {
                    problems += "$folder/${file.name} is missing"
                    continue
                }
                val translated = entries(other)
                (base.keys - translated.keys).forEach { problems += "$folder/${file.name}: no «$it»" }
                (translated.keys - base.keys).forEach { problems += "$folder/${file.name}: «$it» is not in values/" }
                base.forEach { (key, args) ->
                    val theirs = translated[key] ?: return@forEach
                    if (theirs != args) problems += "$folder/${file.name}: «$key» has $theirs, English has $args"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** name → the set of format placeholders (%1$s, %d…) in it, for translatable entries. */
    private fun entries(file: File): Map<String, Set<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableMapOf<String, Set<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            when (e.tagName) {
                "string" -> out[e.getAttribute("name")] = placeholders(e.textContent)
                // Plural forms differ by language; their placeholders are what must agree.
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    val args = (0 until items.length).flatMap { placeholders(items.item(it).textContent) }.toSet()
                    out["plurals:" + e.getAttribute("name")] = args
                }
                "string-array" -> out["array:" + e.getAttribute("name")] =
                    setOf("items=" + e.getElementsByTagName("item").length)
            }
        }
        return out
    }

    private fun placeholders(text: String): Set<String> =
        Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfx]").findAll(text).map { it.value }.toSet()
}
