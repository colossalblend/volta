package com.ars3nb.volta.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPresetsTest {

    @Test
    fun everyNameIsAPackageName() {
        AppPresets.all.forEach { preset ->
            preset.packages.forEach { pkg ->
                assertTrue("${preset.id}: '$pkg'", AppPresets.PACKAGE_NAME.matches(pkg))
            }
        }
    }

    @Test
    fun noDuplicatesWithinASet() {
        AppPresets.all.forEach { preset ->
            val repeated = preset.packages.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("${preset.id} repeats $repeated", repeated.isEmpty())
        }
    }

    @Test
    fun anAppBelongsToOneSetOnly() {
        // Two sets sharing an app would switch each other half off, and an app can't be
        // both "past the VPN" and "through the VPN".
        val owners = AppPresets.all
            .flatMap { preset -> preset.packages.map { it to preset.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertTrue("in several sets: $owners", owners.isEmpty())
    }

    @Test
    fun setsAreWellFormed() {
        assertEquals(AppPresets.all.size, AppPresets.all.map { it.id }.toSet().size)
        AppPresets.all.forEach { preset ->
            assertTrue(preset.id, preset.title.isNotBlank())
            assertTrue(preset.id, preset.packages.size >= 3)
        }
        AppRoute.entries.forEach { route ->
            assertFalse(route.name, AppPresets.forRoute(route).isEmpty())
        }
        assertEquals(
            AppPresets.all.size,
            AppRoute.entries.sumOf { AppPresets.forRoute(it).size }
        )
    }

    @Test
    fun theBigNamesAreWhereTheyBelong() {
        fun owner(pkg: String) = AppPresets.all.single { pkg in it.packages }
        assertEquals("banks", owner("ru.sberbankmobile").id)
        assertEquals("banks", owner("com.idamob.tinkoff.android").id)
        assertEquals("gov", owner("ru.rostel").id)
        assertEquals("vk", owner("ru.oneme.app").id)
        assertEquals(AppRoute.DIRECT, owner("ru.yandex.taxi").route)
        assertEquals("youtube", owner("com.google.android.youtube").id)
        assertEquals(AppRoute.VPN, owner("org.telegram.messenger").route)
        assertEquals(AppRoute.VPN, owner("com.whatsapp").route)
        assertEquals(AppRoute.VPN, owner("com.openai.chatgpt").route)
    }
}
