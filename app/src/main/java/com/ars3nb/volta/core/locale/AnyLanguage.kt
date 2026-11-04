package com.ars3nb.volta.core.locale

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Volta's own words as every language it speaks has them.
 *
 * A few names Volta makes are stored, in the language the app had at the time: a
 * subscription's default name, "My VPN · rules" for a subscription's set, "… · copy".
 * Older builds stored them in Russian. To show such a name in today's language the screen
 * has to recognise it in any of them; this reads a string in each language once and keeps
 * it. A name that matches none is the user's or the provider's and is shown as it is.
 */
object AnyLanguage {

    private val resources = ConcurrentHashMap<String, Resources>()
    private val strings = ConcurrentHashMap<Int, List<String>>()

    /** [id] in every language Volta lists, without repeats. */
    fun all(context: Context, @StringRes id: Int): List<String> = strings.getOrPut(id) {
        AppLanguage.SUPPORTED.mapNotNull { option ->
            runCatching { inLanguage(context, option.tag).getString(id) }.getOrNull()
        }.distinct()
    }

    /** Whether [text] is [id] in one of the languages. */
    fun isAny(context: Context, @StringRes id: Int, text: String): Boolean =
        text.isNotEmpty() && text in all(context, id)

    /** [id] in the language of [context] when [text] is [id] in one of the languages; else [text]. */
    fun translated(context: Context, @StringRes id: Int, text: String): String =
        if (isAny(context, id, text)) context.getString(id) else text

    /**
     * What stands for %1$s in [text] when [text] is the one-argument string [id] in one of
     * the languages ("My VPN" in "My VPN · правила"); null when it is none of them.
     */
    fun argumentOf(context: Context, @StringRes id: Int, text: String): String? =
        all(context, id).firstNotNullOfOrNull { argumentIn(it, text) }

    /** The %1$s of [template] as it stands in [text], or null when [text] isn't [template]. */
    internal fun argumentIn(template: String, text: String): String? {
        val at = template.indexOf(ARG)
        if (at < 0) return null
        val prefix = template.substring(0, at)
        val suffix = template.substring(at + ARG.length)
        if (text.length <= prefix.length + suffix.length) return null
        if (!text.startsWith(prefix) || !text.endsWith(suffix)) return null
        return text.substring(prefix.length, text.length - suffix.length)
    }

    private fun inLanguage(context: Context, tag: String): Resources = resources.getOrPut(tag) {
        val app = context.applicationContext ?: context
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        app.createConfigurationContext(config).resources
    }

    private const val ARG = "%1\$s"
}
