package com.ars3nb.volta.core.locale

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UiTextTest {

    private fun res(tag: String): Resources {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return app.createConfigurationContext(config).resources
    }

    @Test
    fun partsAreJoinedWithTheirSeparator() {
        val text = UiText.Concat(listOf(UiText.Raw("a"), UiText.Raw("b"), UiText.Raw("c")), UiText.Raw(", "))
        assertEquals("a, b, c", text.resolve(res("en")))
        assertEquals("ab", (UiText.Raw("a") + UiText.Raw("b")).resolve(res("en")))
    }

    /** A balancer's verdict, made in the repository, is read in whatever language the screen has. */
    @Test
    fun aNestedVerdictFollowsTheLanguageItIsReadIn() {
        val verdict = UiText.of(
            R.string.servers_balancer_in_group,
            "Home",
            UiText.of(R.string.servers_balancer_picked, "NL-1") + UiText.Raw(" · ") +
                UiText.of(R.string.servers_ms, 48L)
        )
        val en = verdict.resolve(res("en"))
        val ru = verdict.resolve(res("ru"))
        assertNotEquals(en, ru)
        for (text in listOf(en, ru)) {
            assertTrue(text, text.contains("NL-1") && text.contains("48") && text.contains("Home"))
            assertFalse(text, text.contains('%'))
        }
    }
}
