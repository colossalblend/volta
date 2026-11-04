package com.ars3nb.volta.core.locale

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {

    @Test
    fun matchesTagsAndFallsBackToTheBaseLanguage() {
        assertEquals("", AppLanguage.match(""))
        assertEquals("ru", AppLanguage.match("ru"))
        assertEquals("ru", AppLanguage.match("ru-RU,en"))
        assertEquals("pt-BR", AppLanguage.match("pt-BR"))
        assertEquals("zh-CN", AppLanguage.match("zh-CN"))
        assertEquals("ja", AppLanguage.match("ja"))
    }

    @Test
    fun everyLanguageHasItsFolderAndIsInTheLocaleConfig() {
        val res = File("src/main/res")
        val config = File(res, "xml/locales_config.xml").readText()
        AppLanguage.SUPPORTED.forEach { option ->
            assertTrue("${option.tag} missing in locales_config", config.contains("\"${option.tag}\""))
            if (option.tag == "en") return@forEach
            val folder = "values-" + option.tag.replace("-", "-r")
            assertTrue("$folder missing", File(res, folder).isDirectory)
        }
    }
}
