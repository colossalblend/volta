package com.ars3nb.volta.core.server

import java.util.Locale

/**
 * Which country a server is in, read from its name: the flag a provider puts there
 * (🇩🇪), else a country or city named in Russian or English ("Германия", "Frankfurt"),
 * else a two-letter code standing on its own ("DE"). Only for grouping the list; it
 * plays no part in choosing a server.
 */
object Countries {

    data class Country(val code: String, val name: String, val flag: String)

    const val UNKNOWN_CODE = "ZZ"
    val UNKNOWN = Country(UNKNOWN_CODE, "Другие", "🌐")

    fun of(remark: String): Country = codeOf(remark)?.let(::byCode) ?: UNKNOWN

    fun byCode(code: String): Country {
        val upper = code.uppercase()
        if (upper == UNKNOWN_CODE) return UNKNOWN
        val name = Locale("", upper).getDisplayCountry(RU)
            .takeIf { it.isNotBlank() && !it.equals(upper, ignoreCase = true) }
            ?: upper
        return Country(upper, NAME_FIXES[upper] ?: name, flagOf(upper))
    }

    /** 🇩🇪 for "DE": two regional-indicator symbols. */
    fun flagOf(code: String): String =
        code.uppercase().map { c -> String(Character.toChars(0x1F1E6 + (c - 'A'))) }.joinToString("")

    /** [remark] without the leading flag, when the flag is shown next to it anyway. */
    fun withoutLeadingFlag(remark: String): String {
        val trimmed = remark.trimStart()
        if (trimmed.length < 4) return remark
        val first = trimmed.codePointAt(0)
        val second = trimmed.codePointAt(Character.charCount(first))
        if (first !in REGIONAL || second !in REGIONAL) return remark
        val rest = trimmed.substring(Character.charCount(first) + Character.charCount(second))
            .trimStart(' ', '|', '·', '-', '—', ':', ' ')
        return rest.ifBlank { remark }
    }

    internal fun codeOf(remark: String): String? {
        flagCode(remark)?.let { return it }
        val lower = remark.lowercase()
        val words = WORD.findAll(lower).map { it.value }.toList()
        // Several words first ("южная корея"), so "корея" alone can't steal it.
        phrases.forEach { (phrase, code) -> if (lower.contains(phrase)) return code }
        for (word in words) {
            singleWords[word]?.let { return it }
            // Russian endings: "в Германии", "Финляндии".
            stems.firstOrNull { (stem, full, _) ->
                word.startsWith(stem) && word.length <= full.length + 2
            }?.let { return it.third }
        }
        CODE_TOKEN.findAll(remark).map { it.value }.firstOrNull { it in COMMON_CODES }
            ?.let { return if (it == "UK") "GB" else it }
        return null
    }

    private fun flagCode(remark: String): String? {
        var i = 0
        while (i < remark.length) {
            val cp = remark.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (cp in REGIONAL && next < remark.length) {
                val cp2 = remark.codePointAt(next)
                if (cp2 in REGIONAL) {
                    return "${'A' + (cp - 0x1F1E6)}${'A' + (cp2 - 0x1F1E6)}"
                }
            }
            i = next
        }
        return null
    }

    private val RU = Locale("ru")
    private val REGIONAL = 0x1F1E6..0x1F1FF
    private val WORD = Regex("[\\p{L}]+(?:-[\\p{L}]+)?")
    private val CODE_TOKEN = Regex("(?<![A-Za-z])[A-Z]{2}(?![A-Za-z])")

    /** Codes common enough on VPN lists to trust as a bare "DE" / "NL". */
    private val COMMON_CODES = setOf(
        "DE", "NL", "FI", "SE", "NO", "DK", "US", "GB", "UK", "FR", "PL", "EE", "LV", "LT",
        "TR", "KZ", "RU", "JP", "SG", "HK", "CA", "CH", "AT", "ES", "AE", "IL", "UA", "CZ",
        "RO", "MD", "AM", "GE", "IE", "BG", "RS", "HU", "PT", "KR", "AU", "BR", "IN", "BY",
        "UZ", "KG", "AZ", "CY", "GR", "LU", "BE", "SK", "SI", "HR", "IS", "AR", "MX", "TW"
    )

    /** Shorter names than the official ones Locale gives. */
    private val NAME_FIXES = mapOf(
        "US" to "США",
        "GB" to "Великобритания",
        "AE" to "ОАЭ",
        "KR" to "Южная Корея",
        "HK" to "Гонконг",
        "MD" to "Молдова",
        "CZ" to "Чехия"
    )

    private val ALIASES = mapOf(
        "сша" to "US", "usa" to "US", "америка" to "US", "штаты" to "US", "america" to "US",
        "англия" to "GB", "британия" to "GB", "england" to "GB", "britain" to "GB",
        "голландия" to "NL", "holland" to "NL", "оаэ" to "AE", "uae" to "AE",
        "эмираты" to "AE", "emirates" to "AE", "дубай" to "AE", "dubai" to "AE",
        "корея" to "KR", "korea" to "KR", "чехия" to "CZ", "czechia" to "CZ",
        "молдавия" to "MD", "turkey" to "TR", "türkiye" to "TR",
        // Cities that providers name instead of the country.
        "москва" to "RU", "moscow" to "RU", "питер" to "RU", "спб" to "RU",
        "амстердам" to "NL", "amsterdam" to "NL", "роттердам" to "NL",
        "франкфурт" to "DE", "frankfurt" to "DE", "берлин" to "DE", "berlin" to "DE",
        "мюнхен" to "DE", "munich" to "DE", "нюрнберг" to "DE", "nuremberg" to "DE",
        "хельсинки" to "FI", "helsinki" to "FI", "стокгольм" to "SE", "stockholm" to "SE",
        "лондон" to "GB", "london" to "GB", "париж" to "FR", "paris" to "FR",
        "варшава" to "PL", "warsaw" to "PL", "таллин" to "EE", "таллинн" to "EE",
        "tallinn" to "EE", "рига" to "LV", "riga" to "LV", "вильнюс" to "LT",
        "vilnius" to "LT", "стамбул" to "TR", "istanbul" to "TR", "алматы" to "KZ",
        "almaty" to "KZ", "астана" to "KZ", "astana" to "KZ", "токио" to "JP",
        "tokyo" to "JP", "торонто" to "CA", "toronto" to "CA", "цюрих" to "CH",
        "zurich" to "CH", "вена" to "AT", "vienna" to "AT", "милан" to "IT",
        "milan" to "IT", "мадрид" to "ES", "madrid" to "ES", "прага" to "CZ",
        "prague" to "CZ", "бухарест" to "RO", "bucharest" to "RO", "кишинев" to "MD",
        "кишинёв" to "MD", "chisinau" to "MD", "ереван" to "AM", "yerevan" to "AM",
        "тбилиси" to "GE", "tbilisi" to "GE", "осло" to "NO", "oslo" to "NO",
        "копенгаген" to "DK", "copenhagen" to "DK", "дублин" to "IE", "dublin" to "IE",
        "белград" to "RS", "belgrade" to "RS", "будапешт" to "HU", "budapest" to "HU",
        "лиссабон" to "PT", "lisbon" to "PT", "ташкент" to "UZ", "tashkent" to "UZ",
        "бишкек" to "KG", "bishkek" to "KG", "баку" to "AZ", "baku" to "AZ",
        "минск" to "BY", "minsk" to "BY", "киев" to "UA", "kyiv" to "UA",
        "нью-йорк" to "US", "майами" to "US", "miami" to "US", "даллас" to "US",
        "dallas" to "US", "чикаго" to "US", "chicago" to "US", "сиэтл" to "US",
        "seattle" to "US", "фремонт" to "US", "fremont" to "US"
    )

    /** Country names that are also everyday words ("того", "jersey"). */
    private val AMBIGUOUS = setOf("того", "мали", "чад", "гана", "chad", "jersey", "man", "мэн")

    /** Every country's Russian and English name, so no list has to be kept by hand. */
    private val names: List<Pair<String, String>> by lazy {
        Locale.getISOCountries().flatMap { code ->
            listOf(RU, Locale.ENGLISH).map { locale ->
                Locale("", code).getDisplayCountry(locale).lowercase() to code
            }
        }.filter { (name, code) ->
            name.isNotBlank() && name != code.lowercase() && name !in AMBIGUOUS
        } +
            NAME_FIXES.map { (code, name) -> name.lowercase() to code } +
            ALIASES.toList()
    }

    private val phrases: List<Pair<String, String>> by lazy {
        names.filter { it.first.contains(' ') }.sortedByDescending { it.first.length }
    }

    private val singleWords: Map<String, String> by lazy {
        names.filter { !it.first.contains(' ') }.toMap()
    }

    /** (stem, full name, code) for names long enough to be declined safely. */
    private val stems: List<Triple<String, String, String>> by lazy {
        names.filter { (name, _) -> !name.contains(' ') && name.length >= 5 && name.any { it in 'а'..'я' } }
            .map { (name, code) -> Triple(name.dropLast(1), name, code) }
    }
}
