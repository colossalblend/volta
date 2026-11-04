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

    // ------------------------------------------------------------------ no Russian where it doesn't belong

    /**
     * English (the default, so whatever any language lacks) and every language not written
     * in Cyrillic have no Cyrillic at all; nor do the manifest and res/xml (labels there
     * must be @string). Russian left behind in one of them is what a user who picked
     * English, German… would see.
     */
    @Test
    fun englishAndLatinOrOtherScriptLanguagesHaveNoCyrillic() {
        val problems = mutableListOf<String>()
        val folders = listOf("values") + AppLanguage.SUPPORTED
            .filter { it.tag != "en" && it.tag.substringBefore('-') !in CYRILLIC_LANGUAGES }
            .map { "values-" + it.tag.replace("-", "-r") }
        for (folder in folders) {
            val files = File(res, folder).listFiles { f -> f.extension == "xml" }.orEmpty()
            assertTrue("$folder has no files", files.isNotEmpty())
            for (file in files.sortedBy { it.name }) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = doc.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as? Element ?: continue
                    val text = ALLOWED_IN_TEXT.fold(e.textContent) { t, token -> t.replace(token, "") }
                    if (CYRILLIC.containsMatchIn(text)) {
                        problems += "$folder/${file.name}: «${e.getAttribute("name")}» = ${e.textContent.trim().take(80)}"
                    }
                }
            }
        }
        val xml = listOf(File("src/main/AndroidManifest.xml")) +
            File(res, "xml").listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
        for (file in xml) {
            ATTRIBUTE.findAll(file.readText()).filter { CYRILLIC.containsMatchIn(it.value) }.forEach {
                problems += "${file.path}: ${it.value} (use @string)"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * No Russian string literal in the app's Kotlin outside [CODE_ALLOWLIST]: every word the
     * user reads comes from resources. Comments don't count; a string inside a template
     * ("${if (x) "…" else ""}") does.
     */
    @Test
    fun noRussianStringLiteralsInCodeOutsideTheAllowlist() {
        val root = File("src/main/java")
        assertTrue(root.isDirectory)
        val problems = mutableListOf<String>()
        val withRussian = mutableSetOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath.removePrefix("com/ars3nb/volta/")
            val russian = KotlinLiterals.of(file.readText()).filter { CYRILLIC.containsMatchIn(it.text) }
            if (russian.isEmpty()) return@forEach
            withRussian += path
            if (path in CODE_ALLOWLIST) return@forEach
            russian.forEach { problems += "$path:${it.line}: \"${it.text.take(80)}\" — move it to a string resource" }
        }
        (CODE_ALLOWLIST.keys - withRussian).forEach {
            problems += "$it is on CODE_ALLOWLIST but has no Russian literal any more: take it off"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theLiteralScannerSkipsCommentsAndFindsStringsInTemplates() {
        val src = """
            // Главная
            /* Серверы /* вложенный */ ещё */
            val a = 'б'
            val b = "plain ${'$'}{if (x) "внутри" else ""} end"
            val c = ${"\"\"\""}сырая
            строка${"\"\"\""}
            val d = "escaped \" quote"
        """.trimIndent()
        val texts = KotlinLiterals.of(src).map { it.text }
        assertTrue(texts.toString(), "внутри" in texts)
        assertTrue(texts.toString(), texts.any { it.startsWith("сырая") })
        assertTrue(texts.toString(), texts.none { "Главная" in it || "Серверы" in it || "ещё" in it })
        assertTrue(texts.toString(), "escaped \\\" quote" in texts)
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

    private companion object {
        val CYRILLIC = Regex("\\p{IsCyrillic}")

        /** Languages Volta writes in Cyrillic; every other folder must have none. */
        val CYRILLIC_LANGUAGES = setOf("ru", "uk", "be", "kk")

        /** The .рф domain zone, written as people know it next to .ru and .su. */
        val ALLOWED_IN_TEXT = listOf(".рф")

        /** An XML attribute's value. */
        val ATTRIBUTE = Regex("[\\w:]+=\"[^\"]*\"")

        /**
         * The only Kotlin files allowed Russian literals: words Volta reads or matches, and
         * names stored in the database since before Volta spoke other languages. None is
         * shown as it is. Path under com/ars3nb/volta → why.
         */
        val CODE_ALLOWLIST = mapOf(
            "core/locale/AppLanguage.kt" to "each language's name in itself (Русский, Українська…)",
            "core/server/Countries.kt" to "country and city names providers write in server names; " +
                "shown through resources and the system's country names",
            "core/server/ServerClassifier.kt" to "keywords providers put in server names (обход, авто…)",
            "core/routing/RoutingEngine.kt" to "stored names of the built-in sets and rules (database, backups); " +
                "shown through RoutingNames",
            "core/routing/HappRoutingParser.kt" to "stored names of the rules made from a subscription's lists; " +
                "shown through RoutingNames",
            "core/routing/Presets.kt" to "two presets' stored rule names; shown through their label",
            "core/openflux/OpenFluxLink.kt" to "the stored name of a nameless OpenFlux link; " +
                "shown through OpenFluxLink.shownRemark"
        )
    }
}

/** The string literals in Kotlin source, with their lines: comments skipped, templates' code read too. */
internal class KotlinLiterals private constructor(private val src: String) {

    data class Literal(val line: Int, val text: String)

    private var i = 0
    private var line = 1
    private val found = mutableListOf<Literal>()

    private fun at(token: String) = src.startsWith(token, i)

    private fun step(n: Int = 1) {
        repeat(n) {
            if (i < src.length) {
                if (src[i] == '\n') line++
                i++
            }
        }
    }

    /** Code up to the end, or up to the brace closing a template when [inTemplate]. */
    private fun code(inTemplate: Boolean) {
        var depth = 0
        while (i < src.length) {
            when {
                at("//") -> while (i < src.length && src[i] != '\n') step()
                at("/*") -> comment()
                at("\"\"\"") -> string(raw = true)
                src[i] == '"' -> string(raw = false)
                src[i] == '\'' -> char()
                src[i] == '{' -> { depth++; step() }
                src[i] == '}' -> {
                    step()
                    if (inTemplate && depth == 0) return
                    depth--
                }
                else -> step()
            }
        }
    }

    private fun comment() {
        var depth = 0
        do {
            when {
                at("/*") -> { depth++; step(2) }
                at("*/") -> { depth--; step(2) }
                else -> step()
            }
        } while (depth > 0 && i < src.length)
    }

    private fun char() {
        step()
        while (i < src.length && src[i] != '\'' && src[i] != '\n') {
            if (src[i] == '\\') step()
            step()
        }
        step()
    }

    private fun string(raw: Boolean) {
        val quote = if (raw) "\"\"\"" else "\""
        val start = line
        val text = StringBuilder()
        step(quote.length)
        while (i < src.length) {
            when {
                !raw && src[i] == '\\' -> {
                    text.append(src, i, minOf(i + 2, src.length))
                    step(2)
                }
                at("\${") -> {
                    step(2)
                    code(inTemplate = true)
                }
                at(quote) -> {
                    // """a"""" ends with a quote of its own.
                    if (raw) while (at("\"\"\"\"")) { text.append('"'); step() }
                    step(quote.length)
                    break
                }
                !raw && src[i] == '\n' -> break
                else -> {
                    text.append(src[i])
                    step()
                }
            }
        }
        found += Literal(start, text.toString())
    }

    companion object {
        fun of(src: String): List<Literal> = KotlinLiterals(src).apply { code(inTemplate = false) }.found
    }
}
