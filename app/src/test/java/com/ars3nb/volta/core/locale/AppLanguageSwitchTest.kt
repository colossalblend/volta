package com.ars3nb.volta.core.locale

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 12L: no per-app language in the system, Volta keeps it itself. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32], application = Application::class)
class AppLanguageSwitchTest {

    private val savedLocale = Locale.getDefault()

    @After
    fun forget() {
        AppLanguage.noteSwitch(null)
        // A switch sets the default locale; other tests format numbers with it.
        Locale.setDefault(savedLocale)
    }

    @Test
    fun untilASwitchTheAppKeepsTheResourcesItStartedWith() {
        val app = RuntimeEnvironment.getApplication()
        assertSame(app.resources, AppLanguage.applicationResources(app, app.resources))
    }

    @Test
    fun afterASwitchTheAppSpeaksTheNewLanguageAtOnce() {
        val app = RuntimeEnvironment.getApplication()
        val english = app.resources.getString(R.string.vpn_connected)

        AppLanguage.noteSwitch("ru")
        val ru = AppLanguage.applicationResources(app, app.resources)
        assertNotEquals(english, ru.getString(R.string.vpn_connected))
        // Asked again: the same object, nothing is built per call.
        assertSame(ru, AppLanguage.applicationResources(app, app.resources))

        // Back to "as the system".
        AppLanguage.noteSwitch("")
        assertEquals(english, AppLanguage.applicationResources(app, app.resources).getString(R.string.vpn_connected))
    }
}
