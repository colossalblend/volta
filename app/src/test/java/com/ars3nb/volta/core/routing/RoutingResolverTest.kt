package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The isolation contract: rules that arrive with subscription A must never reach
 * subscription B, no matter how the database got into its current shape.
 */
class RoutingResolverTest {

    private val builtIn = RoutingProfileEntity(
        id = 1, name = "Весь трафик", isBuiltIn = true, isActive = true
    )
    private val userProfile = RoutingProfileEntity(id = 2, name = "Мой набор")
    private val providerA = RoutingProfileEntity(
        id = 10, name = "Правила A", sourceSubscriptionId = 100, useProviderRules = true
    )
    private val providerB = RoutingProfileEntity(
        id = 11, name = "Правила B", sourceSubscriptionId = 200, useProviderRules = true
    )
    private val profiles = listOf(builtIn, userProfile, providerA, providerB)

    private fun sub(id: Long, bound: Long = 0, providerJson: String = "") = SubscriptionEntity(
        id = id,
        name = "Sub $id",
        url = "https://example/$id",
        routingProfileId = bound,
        providerRoutingJson = providerJson
    )

    @Test
    fun subscriptionWithoutChoiceUsesItsOwnProviderProfile() {
        assertEquals(providerA, RoutingResolver.resolve(sub(100), profiles))
        assertEquals(providerB, RoutingResolver.resolve(sub(200), profiles))
    }

    @Test
    fun subscriptionCannotInheritAnotherSubscriptionsProfile() {
        // A stale/hand-edited binding pointing at B's private profile is ignored.
        val resolved = RoutingResolver.resolve(sub(100, bound = providerB.id), profiles)

        assertEquals(providerA, resolved)
        assertFalse(resolved?.sourceSubscriptionId == 200L)
    }

    @Test
    fun subscriptionMayBindAnyAppLevelProfile() {
        assertEquals(userProfile, RoutingResolver.resolve(sub(100, bound = userProfile.id), profiles))
        assertEquals(builtIn, RoutingResolver.resolve(sub(200, bound = builtIn.id), profiles))
    }

    @Test
    fun manualServersFallBackToTheAppDefault() {
        assertEquals(builtIn, RoutingResolver.resolve(null, profiles))
    }

    @Test
    fun subscriptionWithoutOwnRulesFallsBackToTheAppDefault() {
        assertEquals(builtIn, RoutingResolver.resolve(sub(999), profiles))
    }

    @Test
    fun choicesNeverListAnotherSubscriptionsProfile() {
        val choices = RoutingResolver.choicesFor(sub(100), profiles)

        assertTrue(choices.contains(builtIn))
        assertTrue(choices.contains(userProfile))
        assertTrue(choices.contains(providerA))
        assertFalse(choices.contains(providerB))
    }

    @Test
    fun providerJsonIsOnlyReadableByItsOwnSubscription() {
        val a = sub(100, providerJson = """{"rules":[]}""")

        assertEquals("""{"rules":[]}""", RoutingResolver.providerJsonFor(a, providerA))
        // Same subscription, but the active profile is an app-level one: no provider rules.
        assertEquals("", RoutingResolver.providerJsonFor(a, userProfile))
        // Another subscription's profile can never unlock A's JSON.
        assertEquals("", RoutingResolver.providerJsonFor(a, providerB))
        assertEquals("", RoutingResolver.providerJsonFor(null, providerA))
    }

    @Test
    fun appDefaultPrefersTheActiveAppLevelProfile() {
        val noneActive = profiles.map { it.copy(isActive = false) }
        // Falls back to a built-in rather than silently using a subscription's rules.
        val fallback = RoutingResolver.appDefault(noneActive)
        assertTrue(fallback!!.isBuiltIn)
        assertEquals(null, fallback.sourceSubscriptionId)
    }

    @Test
    fun privateProfilesAreFlaggedAsSuch() {
        assertTrue(RoutingResolver.isPrivateTo(providerA, 100))
        assertFalse(RoutingResolver.isPrivateTo(providerA, 200))
        assertFalse(RoutingResolver.isPrivateTo(userProfile, 100))
        assertFalse(userProfile.isShareable.not())
        assertTrue(providerA.isShareable.not())
    }
}
