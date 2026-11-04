package com.ars3nb.volta.core.backup

import android.content.res.Resources
import com.ars3nb.volta.R

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
    /** The message, in the language of [res]. */
    fun message(res: Resources): String {
        fun count(id: Int, n: Int) = res.getQuantityString(id, n, n)
        val parts = buildList {
            if (subscriptionsAdded > 0) add(count(R.plurals.settings_backup_count_subscriptions, subscriptionsAdded))
            if (serversAdded > 0) add(count(R.plurals.settings_backup_count_servers, serversAdded))
            if (routingRestored > 0) add(count(R.plurals.settings_backup_count_rules, routingRestored))
            if (settingsRestored > 0) add(res.getString(R.string.settings_backup_item_settings))
        }
        val head = if (parts.isEmpty()) {
            res.getString(
                if (subscriptionsAlreadyThere > 0) R.string.settings_restore_all_there else R.string.settings_restore_nothing
            )
        } else {
            res.getString(R.string.settings_restore_done, parts.joinToString(res.getString(R.string.settings_list_separator)))
        }
        val notes = buildList {
            if (subscriptionsNotLoaded > 0) add(count(R.plurals.settings_restore_not_loaded, subscriptionsNotLoaded))
            if (skipped > 0) add(count(R.plurals.settings_restore_skipped, skipped))
        }
        return (listOf(head) + notes).joinToString(res.getString(R.string.settings_sentence_separator))
    }
}
