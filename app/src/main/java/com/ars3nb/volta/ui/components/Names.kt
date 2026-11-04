package com.ars3nb.volta.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.ars3nb.volta.R
import com.ars3nb.volta.core.locale.AnyLanguage
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.routing.RoutingNames
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.IpInfo
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.SubscriptionEntity

// Names that are stored but were made by Volta, shown in the app's language. A name the
// user typed or the provider sent is shown exactly as it is.

/** A routing set's name: the built-in sets and Volta's "… · rules" / "… · copy" translated. */
@Composable
@ReadOnlyComposable
fun shownProfileName(profile: RoutingProfileEntity): String =
    RoutingNames.profileName(profile, screenContext())

/** A routing set's description, likewise. */
@Composable
@ReadOnlyComposable
fun shownProfileDescription(profile: RoutingProfileEntity): String =
    RoutingNames.profileDescription(profile, screenContext())

/** A subscription's name; the default one Volta gives a nameless subscription translated. */
@Composable
@ReadOnlyComposable
fun shownSubscriptionName(name: String): String = subscriptionName(screenContext(), name)

@Composable
@ReadOnlyComposable
fun shownSubscriptionName(sub: SubscriptionEntity): String = shownSubscriptionName(sub.name)

fun subscriptionName(context: Context, name: String): String =
    if (AnyLanguage.isAny(context, R.string.home_default_subscription_name, name)) {
        context.getString(R.string.home_default_subscription_name)
    } else {
        name
    }

/**
 * A server's name without its leading flag; a nameless OpenFlux link's "OpenFlux · Yandex
 * Docs + direct" in the app's language.
 */
@Composable
@ReadOnlyComposable
fun shownServerName(remark: String): String =
    OpenFluxLink.shownRemark(screenContext(), Countries.withoutLeadingFlag(remark))

/** The country of the address the internet sees, in the app's language. */
@Composable
@ReadOnlyComposable
fun shownIpCountry(info: IpInfo?): String? {
    if (info == null) return null
    if (info.countryCode.length != 2) return info.country
    val country = Countries.byCode(info.countryCode)
    return "${country.flag} ${Countries.name(screenContext().resources, country.code)}"
}

@Composable
@ReadOnlyComposable
private fun screenContext(): Context {
    LocalConfiguration.current
    return LocalContext.current
}
