package com.ars3nb.volta.core.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language. "" means "as the system": Android picks the closest language Volta
 * has (values-xx), English when it has none. Any other value is a BCP-47 tag from
 * [SUPPORTED].
 *
 * Android 13+ keeps a per-app language itself (LocaleManager), which also shows up in the
 * system's "App languages" page; there it recreates the activities on its own. Older
 * Android has nothing of the kind, so the choice is kept here and every Activity, Service
 * and the Application wrap their base context with [wrap].
 */
object AppLanguage {

    /** One language Volta speaks: its tag and its name in itself (never translated). */
    data class Option(val tag: String, val nativeName: String)

    /** Keep in step with res/xml/locales_config.xml and the values-xx folders. */
    val SUPPORTED: List<Option> = listOf(
        Option("en", "English"),
        Option("ru", "Русский"),
        Option("uk", "Українська"),
        Option("be", "Беларуская"),
        Option("kk", "Қазақша"),
        Option("uz", "Oʻzbekcha"),
        Option("az", "Azərbaycanca"),
        Option("tr", "Türkçe"),
        Option("fa", "فارسی"),
        Option("ar", "العربية"),
        Option("zh-CN", "简体中文"),
        Option("es", "Español"),
        Option("de", "Deutsch"),
        Option("fr", "Français"),
        Option("pt-BR", "Português (Brasil)")
    )

    private const val PREFS = "volta_locale"
    private const val KEY = "tag"

    /** The chosen tag, or "" for "as the system". */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) "" else match(locales.toLanguageTags())
        }
        return stored(context)
    }

    /** The name to show for the current choice; [systemLabel] for "as the system". */
    fun currentName(context: Context, systemLabel: String): String {
        val tag = current(context)
        return SUPPORTED.firstOrNull { it.tag == tag }?.nativeName ?: systemLabel
    }

    /** Switches the language and redraws the screen in it. */
    fun set(activity: Activity, tag: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android recreates the activities itself.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            applyToApp(activity.applicationContext, tag)
            activity.recreate()
        }
    }

    /**
     * Android 12 and older: the application's own resources follow the choice at once.
     * The repository, the worker and the country names read them, and the Application was
     * wrapped only once, at start, so without this they would keep the old language until
     * the app restarts.
     */
    @Suppress("DEPRECATION")
    private fun applyToApp(app: Context, tag: String) {
        val locale = if (tag.isEmpty()) {
            Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
        } else {
            Locale.forLanguageTag(tag)
        }
        Locale.setDefault(locale)
        val res = app.resources
        val config = Configuration(res.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        res.updateConfiguration(config, res.displayMetrics)
    }

    /**
     * The base context in the chosen language, for attachBaseContext. Android 13+ does it
     * itself, and "as the system" needs nothing.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    /** "pt-BR,en" → "pt-BR"; a tag Volta doesn't list (set from system settings) → its base. */
    internal fun match(tags: String): String {
        val first = tags.substringBefore(',').trim()
        if (first.isEmpty()) return ""
        SUPPORTED.firstOrNull { it.tag.equals(first, ignoreCase = true) }?.let { return it.tag }
        val base = first.substringBefore('-')
        return SUPPORTED.firstOrNull { it.tag.substringBefore('-').equals(base, ignoreCase = true) }?.tag ?: first
    }
}
