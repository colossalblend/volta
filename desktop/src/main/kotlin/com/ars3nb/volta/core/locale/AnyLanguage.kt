package com.ars3nb.volta.core.locale

import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.Res
import java.util.concurrent.ConcurrentHashMap

/**
 * The Android app's AnyLanguage on Windows: Volta's own words as every language it speaks
 * has them, so a name Volta made and stored in the language of the day ("My VPN · rules",
 * "… · copy", the default subscription name) is recognised in any of them and shown in
 * today's. Read from the same Android texts (see Res).
 */
object AnyLanguage {

    private val strings = ConcurrentHashMap<String, List<String>>()

    /** [id] in every language Volta lists, without repeats. */
    fun all(id: String): List<String> = strings.getOrPut(id) {
        Lang.entries.mapNotNull { lang -> Res.raw(id, lang.tag) }.distinct()
    }

    /** Whether [text] is [id] in one of the languages. */
    fun isAny(id: String, text: String): Boolean = text.isNotEmpty() && text in all(id)

    /** [id] in today's language when [text] is [id] in one of the languages; else [text]. */
    fun translated(id: String, text: String): String = if (isAny(id, text)) Res.string(id) else text

    /** What stands for %1$s in [text] when [text] is the one-argument string [id] in one of the languages. */
    fun argumentOf(id: String, text: String): String? = all(id).firstNotNullOfOrNull { argumentIn(it, text) }

    /** The %1$s of [template] as it stands in [text], or null when [text] isn't [template]. */
    fun argumentIn(template: String, text: String): String? {
        val at = template.indexOf(ARG)
        if (at < 0) return null
        val prefix = template.substring(0, at)
        val suffix = template.substring(at + ARG.length)
        if (text.length <= prefix.length + suffix.length) return null
        if (!text.startsWith(prefix) || !text.endsWith(suffix)) return null
        return text.substring(prefix.length, text.length - suffix.length)
    }

    private const val ARG = "%1\$s"
}
