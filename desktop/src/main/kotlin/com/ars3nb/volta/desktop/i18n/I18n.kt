package com.ars3nb.volta.desktop.i18n

import java.util.Locale

/** The languages Volta for Windows speaks, each named in itself for the picker. */
enum class Lang(val code: String, val nativeName: String, val rtl: Boolean = false) {
    RU("ru", "Русский"),
    EN("en", "English"),
    UK("uk", "Українська"),
    KK("kk", "Қазақша"),
    UZ("uz", "O‘zbekcha"),
    TR("tr", "Türkçe"),
    FA("fa", "فارسی", rtl = true),
    ZH("zh", "中文"),
    ES("es", "Español"),
    DE("de", "Deutsch");

    val strings: Strings
        get() = when (this) {
            RU -> LangRu
            EN -> LangEn
            UK -> LangUk
            KK -> LangKk
            UZ -> LangUz
            TR -> LangTr
            FA -> LangFa
            ZH -> LangZh
            ES -> LangEs
            DE -> LangDe
        }

    val locale: Locale get() = Locale.forLanguageTag(code)

    companion object {
        /** "system" (or anything unknown) follows Windows; otherwise a language code. */
        fun resolve(setting: String): Lang =
            entries.firstOrNull { it.code == setting } ?: fromLocale(Locale.getDefault())

        /** The system language when Volta speaks it; Russian for the rest of the former USSR, else English. */
        fun fromLocale(locale: Locale): Lang {
            val code = locale.language.lowercase()
            entries.firstOrNull { it.code == code }?.let { return it }
            return when (code) {
                "be", "ky", "tg", "hy", "az", "ka", "tk" -> RU
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
