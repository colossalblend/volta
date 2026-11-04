package com.ars3nb.volta.core.backup

import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res

/** How a restore went, for the message after it. */
data class RestoreReport(
    val subscriptionsAdded: Int = 0,
    /** Added, but the servers could not be loaded now (no internet, panel down). */
    val subscriptionsNotLoaded: Int = 0,
    val subscriptionsAlreadyThere: Int = 0,
    val serversAdded: Int = 0,
    val routingRestored: Int = 0,
    val settingsRestored: Int = 0,
    val skipped: Int = 0
) {
    /** The message, in the app's language. */
    fun message(): String {
        fun count(id: String, n: Int) = Res.plural(id, n, n)
        val parts = buildList {
            if (subscriptionsAdded > 0) add(count(R.plurals.settings_backup_count_subscriptions, subscriptionsAdded))
            if (serversAdded > 0) add(count(R.plurals.settings_backup_count_servers, serversAdded))
            if (routingRestored > 0) add(count(R.plurals.settings_backup_count_rules, routingRestored))
            if (settingsRestored > 0) add(Res.string(R.string.settings_backup_item_settings))
        }
        val head = if (parts.isEmpty()) {
            Res.string(
                if (subscriptionsAlreadyThere > 0) R.string.settings_restore_all_there else R.string.settings_restore_nothing
            )
        } else {
            Res.string(R.string.settings_restore_done, parts.joinToString(Res.string(R.string.settings_list_separator)))
        }
        val notes = buildList {
            if (subscriptionsNotLoaded > 0) add(count(R.plurals.settings_restore_not_loaded, subscriptionsNotLoaded))
            if (skipped > 0) add(count(R.plurals.settings_restore_skipped, skipped))
        }
        return (listOf(head) + notes).joinToString(Res.string(R.string.settings_sentence_separator))
    }
}
