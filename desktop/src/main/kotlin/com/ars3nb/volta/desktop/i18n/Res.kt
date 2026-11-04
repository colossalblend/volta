package com.ars3nb.volta.desktop.i18n

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The Android app's own texts, read from its `res/values…/strings….xml` (the build copies
 * them in, see build.gradle.kts), so every screen Windows shares with Android says the
 * very same words in all fifteen languages. Desktop-only texts live in the same format in
 * desktop/src/main/res and are read the same way; a desktop file never overrides an
 * Android key.
 *
 * Lookup goes the way Android's does: the language's own folder, then the default
 * (English) one. A key nobody has comes back as the key itself, never as an empty label.
 */
object Res {

    /** One language's texts. */
    class Table(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>
    ) {
        companion object {
            val EMPTY = Table(emptyMap(), emptyMap(), emptyMap())
        }
    }

    /** Where the XML comes from: the jar's volta-res folder, or a folder in tests. */
    interface Source {
        /** The files of one `values…` folder, each as a stream. */
        fun open(folder: String): List<InputStream>
    }

    private class ClasspathSource : Source {
        private val index: Map<String, List<String>> by lazy {
            val text = Res::class.java.getResourceAsStream("/volta-res/index.txt")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            text.lineSequence().map { it.trim() }.filter { it.contains('/') }
                .groupBy({ it.substringBefore('/') }, { it })
        }

        override fun open(folder: String): List<InputStream> =
            index[folder].orEmpty().mapNotNull { Res::class.java.getResourceAsStream("/volta-res/$it") }
    }

    /** res folders on disk (the Android app's and the desktop's), for tests. */
    class DirSource(private val roots: List<File>) : Source {
        override fun open(folder: String): List<InputStream> = roots.flatMap { root ->
            File(root, folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
                ?.sortedBy { it.name }
                ?.map { it.inputStream() }
                .orEmpty()
        }
    }

    @Volatile
    private var source: Source = run {
        System.getProperty("volta.res.dirs")?.takeIf { it.isNotBlank() }
            ?.let { dirs -> DirSource(dirs.split(File.pathSeparatorChar).map(::File)) }
            ?: ClasspathSource()
    }

    private val tables = ConcurrentHashMap<String, Table>()

    /** For tests: read from [s] from now on. */
    fun useSource(s: Source) {
        source = s
        tables.clear()
    }

    /** The res folder of a language tag: "" → values, "zh-CN" → values-zh-rCN. */
    fun folderOf(tag: String): String {
        if (tag.isBlank() || tag == "en") return "values"
        val lang = tag.substringBefore('-')
        val region = tag.substringAfter('-', "")
        return if (region.isBlank()) "values-$lang" else "values-$lang-r$region"
    }

    fun table(tag: String): Table = tables.getOrPut(folderOf(tag)) { load(folderOf(tag)) }

    private fun load(folder: String): Table {
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val arrays = LinkedHashMap<String, List<String>>()
        for (stream in runCatching { source.open(folder) }.getOrDefault(emptyList())) {
            runCatching {
                stream.use { input ->
                    val factory = DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = false
                        isExpandEntityReferences = false
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                    val doc = factory.newDocumentBuilder().parse(input)
                    val children = doc.documentElement.childNodes
                    for (i in 0 until children.length) {
                        val el = children.item(i) as? Element ?: continue
                        val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                        when (el.tagName) {
                            // The first file to name a key keeps it: Android's files come first.
                            "string" -> strings.putIfAbsent(name, unescape(el.textContent))
                            "plurals" -> if (name !in plurals) {
                                plurals[name] = el.items().associate { it.getAttribute("quantity") to unescape(it.textContent) }
                            }

                            "string-array" -> if (name !in arrays) {
                                arrays[name] = el.items().map { unescape(it.textContent) }
                            }
                        }
                    }
                }
            }
        }
        return Table(strings, plurals, arrays)
    }

    private fun Element.items(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "item") out += n
        }
        return out
    }

    /**
     * A value as Android shows it: runs of white space are one space outside double
     * quotes, the quotes themselves go, and \n, \t, \', \", \\, \@, \? and \uXXXX are
     * their characters.
     */
    fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var quoted = false
        var space = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    if (space) { out.append(' '); space = false }
                    val n = raw[i + 1]
                    when (n) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = raw.substring(i + 2, minOf(raw.length, i + 6))
                            val code = hex.toIntOrNull(16)
                            if (hex.length == 4 && code != null) {
                                out.append(code.toChar())
                                i += 4
                            } else {
                                out.append('u')
                            }
                        }
                        else -> out.append(n)
                    }
                    i += 2
                    continue
                }

                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> space = out.isNotEmpty()
                else -> {
                    if (space) { out.append(' '); space = false }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ lookup

    /** The language in use (see I18n): its tag in Android's terms. */
    val currentTag: String get() = I18n.lang.tag

    fun has(id: String, tag: String = currentTag): Boolean =
        table(tag).strings.containsKey(id) || table("").strings.containsKey(id)

    fun raw(id: String, tag: String = currentTag): String? =
        table(tag).strings[id] ?: table("").strings[id]

    /** `getString(id, args)`: Android's formatting, Java's String.format. */
    fun string(id: String, vararg args: Any?, tag: String = currentTag): String {
        val pattern = raw(id, tag) ?: return id
        return format(pattern, args, tag)
    }

    /** `getQuantityString(id, count, args)`, with the language's plural rules. */
    fun plural(id: String, count: Int, vararg args: Any?, tag: String = currentTag): String {
        val forms = table(tag).plurals[id] ?: table("").plurals[id] ?: return id
        val pattern = forms[PluralRules.select(tag, count)] ?: forms["other"] ?: forms.values.firstOrNull() ?: return id
        return format(pattern, args, tag)
    }

    fun array(id: String, tag: String = currentTag): List<String> =
        table(tag).arrays[id] ?: table("").arrays[id] ?: emptyList()

    private fun format(pattern: String, args: Array<out Any?>, tag: String): String {
        if (args.isEmpty() && !pattern.contains("%%")) return pattern
        return runCatching { String.format(Locale.forLanguageTag(tag.ifBlank { "en" }), pattern, *args) }
            .getOrDefault(pattern)
    }
}

/**
 * Which plural form a number takes (CLDR's rules for integers), for the languages Volta
 * speaks. "one/few/many" for the East Slavic ones, Arabic's six forms, one form for
 * Chinese, "one/other" for the rest (French and Portuguese count 0 as one).
 */
object PluralRules {
    fun select(tag: String, n: Int): String {
        val lang = tag.substringBefore('-').lowercase().ifBlank { "en" }
        val abs = if (n < 0) -n else n
        val mod10 = abs % 10
        val mod100 = abs % 100
        return when (lang) {
            "ru", "uk", "be" -> when {
                mod10 == 1 && mod100 != 11 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }

            "ar" -> when {
                abs == 0 -> "zero"
                abs == 1 -> "one"
                abs == 2 -> "two"
                mod100 in 3..10 -> "few"
                mod100 in 11..99 -> "many"
                else -> "other"
            }

            "zh", "ja" -> "other"
            "fr", "pt", "fa" -> if (abs == 0 || abs == 1) "one" else "other"
            else -> if (abs == 1) "one" else "other"
        }
    }
}
