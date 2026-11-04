package com.ars3nb.volta.ui.tv

import android.content.res.Configuration
import com.ars3nb.volta.ui.components.AppIcon
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TvTest {

    @Test
    fun tvIsTheTvModeOrTheTvLauncher() {
        assertTrue(Tv.isTv(Configuration.UI_MODE_TYPE_TELEVISION, hasLeanback = false))
        assertTrue(Tv.isTv(Configuration.UI_MODE_TYPE_NORMAL, hasLeanback = true))
        assertTrue(Tv.isTv(Configuration.UI_MODE_TYPE_TELEVISION, hasLeanback = true))
        assertFalse(Tv.isTv(Configuration.UI_MODE_TYPE_NORMAL, hasLeanback = false))
        assertFalse(Tv.isTv(Configuration.UI_MODE_TYPE_UNDEFINED, hasLeanback = false))
        assertFalse(Tv.isTv(Configuration.UI_MODE_TYPE_CAR, hasLeanback = false))
    }

    @Test
    fun aTvHasNoTilesAndNoIconPicker() {
        val rows = Tv.settingsRows(tv = true, sdk = 34)
        assertEquals(TileRows.NONE, rows.tiles)
        assertFalse(rows.appIcon)
        // Old TVs too.
        assertEquals(TileRows.NONE, Tv.settingsRows(tv = true, sdk = 28).tiles)
    }

    @Test
    fun aPhoneKeepsTilesAndIconPicker() {
        assertEquals(SettingsRows(TileRows.ADD_BUTTONS, appIcon = true), Tv.settingsRows(tv = false, sdk = 33))
        assertEquals(SettingsRows(TileRows.ADD_BUTTONS, appIcon = true), Tv.settingsRows(tv = false, sdk = 36))
        // Before Android 13 a tile can't be added from the app: only the how-to hint.
        assertEquals(SettingsRows(TileRows.HOW_TO, appIcon = true), Tv.settingsRows(tv = false, sdk = 32))
        assertEquals(SettingsRows(TileRows.HOW_TO, appIcon = true), Tv.settingsRows(tv = false, sdk = 26))
    }

    // ------------------------------------------------------------------ manifest

    private val manifest: Element by lazy {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File)
            .first { it.isFile }
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun Element.categories(): Set<String> =
        children("category").map { it.android("name") }.toSet()

    @Test
    fun nothingKeepsTheAppOffATv() {
        val features = manifest.children("uses-feature").associate { it.android("name") to it.android("required") }
        assertEquals("false", features["android.software.leanback"])
        assertEquals("false", features["android.hardware.touchscreen"])
        // Whatever else is declared must be optional too.
        features.forEach { (name, required) -> assertEquals(name, "false", required) }
    }

    @Test
    fun theTvLauncherGetsABanner() {
        val app = manifest.children("application").single()
        assertEquals("@drawable/tv_banner", app.android("banner"))
        assertTrue(File(
            listOf("src/main/res/drawable", "app/src/main/res/drawable").map(::File).first { it.isDirectory },
            "tv_banner.xml"
        ).isFile)
    }

    @Test
    fun exactlyOneLauncherEntryOnPhoneAndTv() {
        val aliases = manifest.children("activity-alias")
        val launchers = aliases.filter { alias ->
            alias.children("intent-filter").any { "android.intent.category.LAUNCHER" in it.categories() }
        }
        // The icon picker swaps between these, and only these.
        assertEquals(AppIcon.entries.map { ".${it.alias}" }.toSet(), launchers.map { it.android("name") }.toSet())
        // Each one is on the TV launcher too, so the one enabled alias is the one tile.
        launchers.forEach { alias ->
            assertTrue(
                alias.android("name"),
                alias.children("intent-filter").any { "android.intent.category.LEANBACK_LAUNCHER" in it.categories() }
            )
        }
        assertEquals(listOf(".IconFold"), launchers.filter { it.android("enabled") == "true" }.map { it.android("name") })
        // No activity is on either launcher by itself, or there would be a second entry.
        manifest.children("activity").forEach { activity ->
            val cats = activity.children("intent-filter").flatMap { it.categories() }
            assertFalse(activity.android("name"), "android.intent.category.LAUNCHER" in cats)
            assertFalse(activity.android("name"), "android.intent.category.LEANBACK_LAUNCHER" in cats)
        }
    }
}
