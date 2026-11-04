package com.ars3nb.volta.core.locale

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.routing.HappRoutingParser
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.routing.RoutingNames
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.ui.components.subscriptionName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Names Volta made and stored, in Russian by older builds or in whatever language the app
 * had then, are shown in the language the app has now. The user's and the provider's own
 * names stay as they are.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class StoredNamesTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun builtInSetsAreShownInEnglish() {
        val all = RoutingEngine.builtInProfiles()
        assertEquals(
            listOf("All traffic through VPN", "Russian sites direct"),
            all.map { RoutingNames.profileName(it, context) }
        )
        all.forEach { assertEquals(false, RoutingNames.profileDescription(it, context).any(::isCyrillic)) }
    }

    @Test
    fun aSubscriptionsSetMadeInRussianIsShownInEnglish() {
        val set = RoutingProfileEntity(
            name = "Мой VPN · правила",
            description = "Из подписки «Мой VPN»",
            sourceSubscriptionId = 1
        )
        assertEquals("Мой VPN · rules", RoutingNames.profileName(set, context))
        assertEquals("From the subscription “Мой VPN”", RoutingNames.profileDescription(set, context))
        // Made while the app was in German.
        val german = set.copy(name = "Mein VPN · Regeln", description = "Aus dem Abo „Mein VPN“")
        assertEquals("Mein VPN · rules", RoutingNames.profileName(german, context))
        assertEquals("From the subscription “Mein VPN”", RoutingNames.profileDescription(german, context))
        // Happ's own default.
        assertEquals(
            "Subscription rules",
            RoutingNames.profileName(set.copy(name = HappRoutingParser.DEFAULT_NAME), context)
        )
        // A name the provider sent.
        assertEquals("Провайдер · Обход", RoutingNames.profileName(set.copy(name = "Провайдер · Обход"), context))
    }

    @Test
    fun aCopyOfABuiltInSetIsShownInEnglish() {
        val copy = RoutingProfileEntity(
            name = RoutingEngine.BUILTIN_RU_NAME + " · копия",
            description = "Свои правила на основе «" + RoutingEngine.BUILTIN_RU_NAME + "»"
        )
        assertEquals("Russian sites direct · copy", RoutingNames.profileName(copy, context))
        assertEquals("Your own rules based on “Russian sites direct”", RoutingNames.profileDescription(copy, context))
        // The user's own set.
        val own = RoutingProfileEntity(name = "Мои правила", description = "для дома")
        assertEquals("Мои правила", RoutingNames.profileName(own, context))
        assertEquals("для дома", RoutingNames.profileDescription(own, context))
    }

    @Test
    fun theDefaultSubscriptionNameIsShownInEnglish() {
        assertEquals("Subscription", subscriptionName(context, "Подписка"))
        assertEquals("Subscription", subscriptionName(context, "Abo"))
        assertEquals("Мой VPN", subscriptionName(context, "Мой VPN"))
    }

    @Test
    fun aNamelessOpenFluxLinkIsShownInEnglish() {
        assertEquals(
            "OpenFlux · Yandex Docs + direct",
            OpenFluxLink.shownRemark(context, "OpenFlux · Яндекс Документы + напрямую")
        )
        assertEquals("OpenFlux · Mail.ru", OpenFluxLink.shownRemark(context, "OpenFlux · Mail.ru"))
        // Named by the provider: as it is.
        assertEquals("Финляндия · Яндекс", OpenFluxLink.shownRemark(context, "Финляндия · Яндекс"))
        assertEquals("OpenFlux · Мой сервер", OpenFluxLink.shownRemark(context, "OpenFlux · Мой сервер"))
    }

    @Test
    @Config(qualifiers = "de")
    fun inGermanToo() {
        val set = RoutingProfileEntity(name = "Мой VPN · правила", sourceSubscriptionId = 1)
        assertEquals("Мой VPN · Regeln", RoutingNames.profileName(set, context))
        assertEquals("Abo", subscriptionName(context, "Подписка"))
        assertEquals("Russische Websites direkt", RoutingNames.profileName(RoutingEngine.builtInProfiles()[1], context))
    }

    @Test
    fun argumentOfATemplate() {
        assertEquals("X", AnyLanguage.argumentIn("%1\$s · rules", "X · rules"))
        assertEquals("X", AnyLanguage.argumentIn("From “%1\$s”", "From “X”"))
        assertNull(AnyLanguage.argumentIn("%1\$s · rules", " · rules"))
        assertNull(AnyLanguage.argumentIn("%1\$s · rules", "X · copy"))
        assertNull(AnyLanguage.argumentIn("no argument", "no argument"))
    }

    private fun isCyrillic(c: Char) = Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC
}
