package com.ars3nb.volta.desktop.i18n

import java.util.Locale

/**
 * The languages Volta speaks, the same fifteen as the Android app (AppLanguage.SUPPORTED),
 * each named in itself for the picker. [code] is what the settings store ("zh" from
 * before Volta for Windows used Android's tags stays as it was); [tag] is the Android
 * resource language, whose texts the screens show.
 */
enum class Lang(val code: String, val tag: String, val nativeName: String, val rtl: Boolean = false) {
    EN("en", "en", "English"),
    RU("ru", "ru", "Русский"),
    UK("uk", "uk", "Українська"),
    BE("be", "be", "Беларуская"),
    KK("kk", "kk", "Қазақша"),
    UZ("uz", "uz", "Oʻzbekcha"),
    AZ("az", "az", "Azərbaycanca"),
    TR("tr", "tr", "Türkçe"),
    FA("fa", "fa", "فارسی", rtl = true),
    AR("ar", "ar", "العربية", rtl = true),
    ZH("zh", "zh-CN", "简体中文"),
    ES("es", "es", "Español"),
    DE("de", "de", "Deutsch"),
    FR("fr", "fr", "Français"),
    PT("pt-BR", "pt-BR", "Português (Brasil)");

    /** The typed words of this language, with the Android app's translations where it has them. */
    val strings: Strings get() = matched.getValue(this)

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        private val own: Map<Lang, Strings> = mapOf(
            RU to LangRu, EN to LangEn, UK to LangUk, KK to LangKk, UZ to LangUz,
            TR to LangTr, FA to LangFa, ZH to LangZh, ES to LangEs, DE to LangDe,
            // Their own word lists are the Android app's; what it lacks comes in the
            // nearest language Volta for Windows had before.
            BE to LangRu, AZ to LangEn, AR to LangEn, FR to LangEn, PT to LangEn
        )

        private val matched: Map<Lang, Strings> by lazy {
            entries.associateWith { AndroidMatched(it.tag, own.getValue(it)) }
        }

        /** "system" (or anything unknown) follows Windows; otherwise a language code. */
        fun resolve(setting: String): Lang =
            entries.firstOrNull { it.code.equals(setting, true) || it.tag.equals(setting, true) }
                ?: fromLocale(Locale.getDefault())

        /**
         * The system language when Volta speaks it, as Android picks the closest values-xx;
         * Russian for the rest of the former USSR, else English.
         */
        fun fromLocale(locale: Locale): Lang {
            val code = locale.language.lowercase()
            if (code == "pt") return PT
            entries.firstOrNull { it.tag.substringBefore('-') == code }?.let { return it }
            return when (code) {
                "ky", "tg", "hy", "ka", "tk" -> RU
                "ug" -> KK
                else -> EN
            }
        }
    }
}

/**
 * The language in use right now. The settings screen changes it; code outside the UI
 * (errors thrown deep in the core, the tray) reads [s] at the moment it needs a word.
 */
object I18n {
    @Volatile
    var lang: Lang = Lang.fromLocale(Locale.getDefault())
        private set

    val s: Strings get() = lang.strings

    fun use(setting: String) {
        lang = Lang.resolve(setting)
    }
}

/** "1 сервер / 3 сервера / 12 серверов": the three Slavic forms. */
fun slavic(n: Long, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

fun slavic(n: Int, one: String, few: String, many: String): String = slavic(n.toLong(), one, few, many)
