package com.ars3nb.volta.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

class CountriesTest {

    @Test
    fun flagInTheNameWins() {
        assertEquals("FI", Countries.of("🇫🇮 Финляндия · Hysteria2 🛡️").code)
        assertEquals("RU", Countries.of("🇷🇺 Обход LTE #2 ❤️").code)
        assertEquals("NL", Countries.of("🇳🇱 Для LTE 4 ✦ Reality").code)
    }

    @Test
    fun countryAndCityNamesInEitherLanguage() {
        assertEquals("DE", Countries.of("Германия Premium").code)
        assertEquals("FI", Countries.of("Сервер в Финляндии").code)
        assertEquals("NL", Countries.of("Amsterdam #3").code)
        assertEquals("US", Countries.of("США · Нью-Йорк").code)
        assertEquals("KR", Countries.of("Южная Корея").code)
        assertEquals("SE", Countries.of("Sweden fast").code)
    }

    @Test
    fun bareCodesOnlyWhenTheyStandAlone() {
        assertEquals("DE", Countries.of("DE-1 vless").code)
        assertEquals("GB", Countries.of("UK London").code)
        // "LTE", "TCP" and lowercase words must not be read as codes.
        assertEquals(Countries.UNKNOWN_CODE, Countries.of("Обход LTE TCP").code)
    }

    @Test
    fun everydayWordsAreNotCountries() {
        assertEquals(Countries.UNKNOWN_CODE, Countries.of("Индивидуальный тариф").code)
        assertEquals(Countries.UNKNOWN_CODE, Countries.of("Для того чтобы").code)
        assertEquals(Countries.UNKNOWN_CODE, Countries.of("Австрийский провайдер").code)
    }

    @Test
    fun namesAndFlags() {
        val de = Countries.byCode("de")
        assertEquals("Германия", de.name)
        assertEquals("🇩🇪", de.flag)
        assertEquals("США", Countries.byCode("US").name)
    }

    @Test
    fun leadingFlagIsDroppedOnlyWhenThereIsOne() {
        assertEquals("Финляндия · Hysteria2", Countries.withoutLeadingFlag("🇫🇮 Финляндия · Hysteria2"))
        assertEquals("Финляндия", Countries.withoutLeadingFlag("Финляндия"))
        assertEquals("🇫🇮", Countries.withoutLeadingFlag("🇫🇮"))
    }
}
