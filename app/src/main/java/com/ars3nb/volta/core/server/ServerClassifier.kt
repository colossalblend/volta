package com.ars3nb.volta.core.server

import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole

/** Assigns a useful default role when a provider does not describe one. */
object ServerClassifier {
    /**
     * Provider balancers: «Авто», «Автовыбор», «Автобалансер», «Балансер», "Auto",
     * "Load balance", "URL-test", "Fallback". Checked before the LTE markers, so a
     * «Балансер LTE» is still a balancer.
     */
    private val autoWords = Regex(
        "(auto|авто|баланс|balanc|url.?test|fallback|лучший\\s*сервер|best\\s*server)",
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

    fun classify(remark: String): ServerRole {
        ServerMarkup.prefixRole(remark)?.let { return it.role }
        return when {
            autoWords.containsMatchIn(remark.trim()) -> ServerRole.AUTO
            bypassWords.containsMatchIn(remark.trim()) -> ServerRole.BYPASS_WHITELIST
            else -> ServerRole.NORMAL
        }
    }

    /**
     * The old name check, minus the groups the provider listed explicitly: a group the
     * provider marked is complete, so a stray "LTE" in an unmarked name doesn't add to it.
     */
    fun roleByName(remark: String, explicit: Set<ServerRole> = emptySet()): ServerRole {
        val guess = classify(remark)
        return if (guess != ServerRole.NORMAL && guess in explicit) ServerRole.NORMAL else guess
    }

    /**
     * The group of a server nobody marked: the name check, and for OpenFlux what its
     * carriers say. An OpenFlux server whose carriers all ride Russian services
     * (Yandex and Mail.ru documents, Cups, Avito…) and never dial the exit itself is
     * what still works when a mobile network lets only white-listed sites through, so
     * its name saying nothing puts it in LTE. One that dials its exit directly stays
     * Home, like any VPN server. As with names, a group the provider marked explicitly
     * stays as the provider made it.
     */
    fun autoRole(server: ServerEntity, explicit: Set<ServerRole> = emptySet()): ServerRole {
        val byName = roleByName(server.remark, explicit)
        if (byName != ServerRole.NORMAL || server.protocol != ProtocolType.OPENFLUX) return byName
        return if (ridesServicesOnly(server) && ServerRole.BYPASS_WHITELIST !in explicit) {
            ServerRole.BYPASS_WHITELIST
        } else {
            ServerRole.NORMAL
        }
    }

    /** An OpenFlux server with only service carriers ([OpenFluxLink.SERVICE_CARRIERS]). */
    fun ridesServicesOnly(server: ServerEntity): Boolean {
        if (server.protocol != ProtocolType.OPENFLUX) return false
        val carriers = server.network.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        return carriers.isNotEmpty() && carriers.all { it in OpenFluxLink.SERVICE_CARRIERS }
    }

    fun classify(server: ServerEntity): ServerEntity = classify(server, ServerMarkup.TypeMap.EMPTY, -1)

    /**
     * One server's group, provider markup first (see [ServerMarkup]):
     * JSON group (already set, [ServerEntity.roleFromProvider]) → `[L]/[H]/[A]` prefix →
     * `server-type` position ([position] is 0-based) → the name check ([autoRole]).
     * The same for every protocol, OpenFlux included.
     */
    fun classify(
        server: ServerEntity,
        types: ServerMarkup.TypeMap,
        position: Int,
        explicit: Set<ServerRole> = types.explicit
    ): ServerEntity {
        val prefixed = ServerMarkup.prefixRole(server.remark)
        val remark = prefixed?.remark ?: server.remark
        val providerRole = when {
            server.roleFromProvider -> server.role
            prefixed != null -> prefixed.role
            else -> types.roles[position + 1]
        }
        return if (providerRole != null) {
            server.copy(remark = remark, role = providerRole, roleFromProvider = true, roleManual = false)
        } else {
            server.copy(
                role = autoRole(server.copy(remark = remark), explicit),
                roleFromProvider = false,
                roleManual = false
            )
        }
    }

    /** Every group the provider marked anywhere in this list (for [roleByName]). */
    fun explicitGroups(incoming: List<ServerEntity>, types: ServerMarkup.TypeMap): Set<ServerRole> =
        buildSet {
            addAll(types.explicit)
            incoming.forEach { s ->
                when {
                    s.roleFromProvider -> add(s.role)
                    else -> ServerMarkup.prefixRole(s.remark)?.let { add(it.role) }
                }
            }
        }

    /**
     * Identity of a node across refreshes.
     *
     * Includes the transport: providers routinely publish the same host/port/uuid several
     * times with different networks (ws and grpc, or two paths). Keying on address alone
     * made them collide, and the collision deleted every duplicate but one on each refresh.
     * Cosmetic churn (remark, SNI, fingerprint) is still ignored so measured health survives.
     */
    fun stableKey(server: ServerEntity): String {
        if (server.protocol == ProtocolType.OPENFLUX) {
            // Its address is often just the document service (docs.yandex.ru:443), the
            // same for every exit: the link's carriers and context tell exits apart.
            val identity = OpenFluxLink.parse(server.rawLink)?.identity ?: server.rawLink.trim()
            return "${server.protocol.name}|$identity"
        }
        return xrayKey(server)
    }

    private fun xrayKey(server: ServerEntity): String = listOf(
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
        subscriptionId: Long,
        /** `server-type` from the import link, as stored with the subscription. */
        serverTypes: String = ""
    ): List<ServerEntity> {
        val oldByKey = previous.associateBy(::stableKey)
        val types = ServerMarkup.parseServerTypes(serverTypes)
        val explicit = explicitGroups(incoming, types)
        // Two incoming rows must never claim the same stored id, or the REPLACE insert
        // would collapse them into one and silently drop a server.
        val claimed = mutableSetOf<Long>()
        return incoming.mapIndexed { index, raw ->
            val classified = classify(
                raw.copy(subscriptionId = subscriptionId, sortOrder = index),
                types,
                index,
                explicit
            )
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
