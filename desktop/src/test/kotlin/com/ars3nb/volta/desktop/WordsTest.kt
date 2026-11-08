package com.ars3nb.volta.desktop

import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.Res
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Android text the Windows screens name (R.string.…, R.plurals.…, R.array.…) is
 * there in every one of the fifteen languages, in that language's own file: none falls
 * back to English or shows its key. A text added on Android and used here without its
 * translations, or a desktop text added in one language only, stops the build.
 */
class WordsTest {

    private val sources: List<File> = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin"))
        .firstOrNull { it.isDirectory }
        ?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList()
        .orEmpty()

    private fun used(kind: String): Set<String> {
        val pattern = Regex("""\bR\.$kind\.([A-Za-z0-9_]+)""")
        return sources.flatMap { f -> pattern.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
    }

    @Test
    fun everyUsedTextIsThereInEveryLanguage() {
        assertTrue("no sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val strings = used("string")
        val plurals = used("plurals")
        val arrays = used("array")
        assertTrue("found only ${strings.size} texts", strings.size > 100)
        // The words this sync brought: the traffic card, switching, checks, Gemini, Lite.
        listOf(
            "home_traffic_day_description", "home_state_switching", "home_switch_back", "servers_check_progress",
            "gemini_check_action", "gemini_reason_unsure", "settings_lite_mode", "desktop_lite_mode_hint",
            "settings_auto_by_subscription_title", "home_auto_scope_label", "desktop_traffic_tap_hint"
        ).forEach { assertTrue("$it is not used", it in strings) }
        val missing = mutableListOf<String>()
        Lang.entries.forEach { lang ->
            val table = Res.table(lang.tag)
            strings.filter { it !in table.strings || table.strings[it].isNullOrBlank() }.forEach { missing += "${lang.tag}: $it" }
            plurals.filter { it !in table.plurals }.forEach { missing += "${lang.tag}: plurals $it" }
            arrays.filter { it !in table.arrays }.forEach { missing += "${lang.tag}: array $it" }
        }
        assertTrue("missing in their language:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
