package com.ars3nb.volta.core.server

import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole

/** Assigns a useful default role when a provider does not describe one. */
object ServerClassifier {
    private val autoWords = Regex(
        "(auto|авто|autobalancer|auto.?balance|автоподбор|автобаланс|балансиров)",
        RegexOption.IGNORE_CASE
    )
    /**
     * Markers providers put on whitelist-bypass ("LTE") nodes: the classic «обход» /
     * «белые списки» wording plus the LTE / mobile-internet tags. `LTE` must stand on
     * its own so a host name that merely contains the letters is not caught.
     */
    private val bypassWords = Regex(
        "(обход|бел(?:ый|ые|ого)?\\s*спис|white.?list|whitelist|глушилк|анти.?глуш|anti.?glush|" +
            "bypass|(?<![\\p{L}\\d])(?:lte|4g|5g)(?![\\p{L}\\d])|мобильн|моб\\.?\\s*инет|mobile)",
        RegexOption.IGNORE_CASE
    )

    fun classify(remark: String): ServerRole = when {
        autoWords.containsMatchIn(remark.trim()) -> ServerRole.AUTO
        bypassWords.containsMatchIn(remark.trim()) -> ServerRole.BYPASS_WHITELIST
        else -> ServerRole.NORMAL
    }

    fun classify(server: ServerEntity): ServerEntity =
        server.copy(role = classify(server.remark), roleManual = false)

    /**
     * Identity of a node across refreshes.
     *
     * Includes the transport: providers routinely publish the same host/port/uuid several
     * times with different networks (ws and grpc, or two paths). Keying on address alone
     * made them collide, and the collision deleted every duplicate but one on each refresh.
     * Cosmetic churn (remark, SNI, fingerprint) is still ignored so measured health survives.
     */
    fun stableKey(server: ServerEntity): String = listOf(
        server.protocol.name,
        server.address.trim().lowercase(),
        server.port,
        server.uuid.trim(),
        server.network.trim().lowercase(),
        server.security.trim().lowercase(),
        server.path.trim(),
        server.serviceName.trim()
    ).joinToString("|")

    /**
     * Applies a fresh provider list over the stored one, carrying user state
     * (selection, favourite, manual role) and measured health across the update.
     */
    fun mergeProviderUpdate(
        previous: List<ServerEntity>,
        incoming: List<ServerEntity>,
        subscriptionId: Long
    ): List<ServerEntity> {
        val oldByKey = previous.associateBy(::stableKey)
        // Two incoming rows must never claim the same stored id, or the REPLACE insert
        // would collapse them into one and silently drop a server.
        val claimed = mutableSetOf<Long>()
        return incoming.mapIndexed { index, raw ->
            val classified = classify(raw.copy(subscriptionId = subscriptionId, sortOrder = index))
            val old = oldByKey[stableKey(classified)]
                ?.takeIf { claimed.add(it.id) }
                ?: return@mapIndexed classified
            classified.copy(
                id = old.id,
                isSelected = old.isSelected,
                isFavorite = old.isFavorite,
                role = if (old.roleManual) old.role else classified.role,
                roleManual = old.roleManual,
                latencyMs = old.latencyMs,
                ewmaMs = old.ewmaMs,
                failStreak = old.failStreak,
                lastPingAt = old.lastPingAt,
                tags = classified.tags.ifBlank { old.tags }
            )
        }
    }
}
