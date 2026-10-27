package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.SubscriptionEntity

/**
 * Single source of truth for "which routing rules does this server run with".
 *
 * The isolation guarantee lives here: a profile stamped with a
 * [RoutingProfileEntity.sourceSubscriptionId] belongs to that subscription and to
 * nothing else. Both the config builder and the UI ask this object, so what the user
 * sees on screen is exactly what the tunnel will use.
 */
object RoutingResolver {

    /** The profile that will actually be used for [sub] (null = manual/unbound server). */
    fun resolve(
        sub: SubscriptionEntity?,
        profiles: List<RoutingProfileEntity>
    ): RoutingProfileEntity? {
        val bound = sub?.routingProfileId
            ?.takeIf { it > 0 }
            ?.let { id -> profiles.firstOrNull { it.id == id } }
            ?.takeIf { it.sourceSubscriptionId == null || it.sourceSubscriptionId == sub.id }
        if (bound != null) return bound

        // No explicit choice: a subscription that shipped its own rules uses them.
        sub?.let { s -> profiles.firstOrNull { it.sourceSubscriptionId == s.id } }
            ?.let { return it }

        return appDefault(profiles)
    }

    /** The app-level default, used by manual servers and unbound subscriptions. */
    fun appDefault(profiles: List<RoutingProfileEntity>): RoutingProfileEntity? =
        profiles.firstOrNull { it.isActive && it.sourceSubscriptionId == null }
            ?: profiles.firstOrNull { it.isBuiltIn }

    /** Profiles this subscription may be bound to. Another subscription's are excluded. */
    fun choicesFor(
        sub: SubscriptionEntity?,
        profiles: List<RoutingProfileEntity>
    ): List<RoutingProfileEntity> = profiles.filter {
        it.sourceSubscriptionId == null || it.sourceSubscriptionId == sub?.id
    }

    /**
     * Provider rule JSON is readable only by the subscription that shipped it, and only
     * while that subscription's own profile is the active one.
     */
    fun providerJsonFor(
        sub: SubscriptionEntity?,
        profile: RoutingProfileEntity?
    ): String {
        val owner = profile?.sourceSubscriptionId ?: return ""
        return if (sub != null && owner == sub.id) sub.providerRoutingJson else ""
    }

    /** True when a profile came from a subscription and is private to it. */
    fun isPrivateTo(profile: RoutingProfileEntity, subscriptionId: Long?): Boolean =
        profile.sourceSubscriptionId != null && profile.sourceSubscriptionId == subscriptionId
}
