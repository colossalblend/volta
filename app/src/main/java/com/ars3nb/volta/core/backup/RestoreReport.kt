package com.ars3nb.volta.core.backup

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
    fun message(): String {
        val parts = buildList {
            if (subscriptionsAdded > 0) add("подписки: $subscriptionsAdded")
            if (serversAdded > 0) add("свои серверы: $serversAdded")
            if (routingRestored > 0) add("наборы правил: $routingRestored")
            if (settingsRestored > 0) add("настройки")
        }
        val head = if (parts.isEmpty()) {
            if (subscriptionsAlreadyThere > 0) "Всё из файла уже есть в Volta" else "Нечего восстанавливать"
        } else {
            "Готово. Восстановлено — " + parts.joinToString(", ")
        }
        val notes = buildList {
            if (subscriptionsNotLoaded > 0) {
                add(
                    "Серверы не загрузились у подписок: $subscriptionsNotLoaded. " +
                        "Обновите их позже на вкладке «Подписки»"
                )
            }
            if (skipped > 0) add("Не прочитано записей: $skipped")
        }
        return (listOf(head) + notes).joinToString(". ")
    }
}
