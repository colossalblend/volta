package com.ars3nb.volta.data.repository

import android.content.Context
import com.ars3nb.volta.core.HwidProvider
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.ping.IpChecker
import com.ars3nb.volta.core.ping.NetworkProbe
import com.ars3nb.volta.core.ping.PingEngine
import com.ars3nb.volta.core.routing.GeoDataManager
import com.ars3nb.volta.core.routing.GeoNames
import com.ars3nb.volta.core.routing.HappRoutingParser
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.core.server.ServerClassifier
import com.ars3nb.volta.core.vpn.CertPins
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.db.VoltaDatabase
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.IpInfo
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.model.matches
import com.ars3nb.volta.data.prefs.AppPreferences
import com.ars3nb.volta.data.prefs.DesignTheme
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class VoltaRepository(private val context: Context) {

    private val db = VoltaDatabase.get(context)
    private val prefs = AppPreferences(context)
    private val subDao = db.subscriptionDao()
    private val serverDao = db.serverDao()
    private val routingDao = db.routingDao()
    private val geoData = GeoDataManager(context.filesDir)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * The same client routed through the running tunnel's HTTP inbound. When the local
     * network blocks a provider's panel (typical on a whitelisted LTE), Happ and Hiddify
     * retry the subscription through the VPN; so do we.
     */
    private val tunnelHttp: OkHttpClient by lazy {
        http.newBuilder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", LOCAL_HTTP_PORT)))
            .build()
    }

    /** Serialises connect requests from the button, the tiles, boot and Always-on. */
    private val connectMutex = kotlinx.coroutines.sync.Mutex()

    val subscriptions: Flow<List<SubscriptionEntity>> = subDao.observeAll()
    val servers: Flow<List<ServerEntity>> = serverDao.observeAll()
    val selectedServer: Flow<ServerEntity?> = serverDao.observeSelected()
    val routingProfiles: Flow<List<RoutingProfileEntity>> = routingDao.observeAll()
    val shareableRoutingProfiles: Flow<List<RoutingProfileEntity>> = routingDao.observeShareable()
    val activeRouting: Flow<RoutingProfileEntity?> = routingDao.observeActive()

    val vpnState = VoltaVpnService.state
    val vpnError = VoltaVpnService.error
    val traffic = VoltaVpnService.traffic
    val connectedSince = VoltaVpnService.connectedSince

    val designTheme = prefs.designTheme
    val autoFailover = prefs.autoFailover
    val perAppMode = prefs.perAppMode
    val perAppPackages = prefs.perAppPackages
    val pingOnStart = prefs.pingOnStart
    val defaultPingType = prefs.defaultPingType
    val batterySaver = prefs.batterySaver
    val sendHwid = prefs.sendHwid
    val autoConnect = prefs.autoConnect
    val localDns = prefs.localDns
    val connectMode = prefs.connectMode
    val refreshOnLaunch = prefs.refreshOnLaunch
    val followNetwork = prefs.followNetwork
    val autoRecheck = prefs.autoRecheck
    val hideBalancers = prefs.hideBalancers
    val lastRegime = prefs.networkRegime

    private val _autoPicking = MutableStateFlow(false)

    /** True while a connect is choosing its node, whoever asked (button, tile, boot). */
    val autoPicking: StateFlow<Boolean> = _autoPicking.asStateFlow()

    private val _pickPhase = MutableStateFlow<AutoBalancer.Phase?>(null)
    val pickPhase: StateFlow<AutoBalancer.Phase?> = _pickPhase.asStateFlow()

    private val _lastDecision = MutableStateFlow<AutoBalancer.Decision?>(null)

    /** The latest auto pick with its measurements, for the "why this node" card. */
    val lastDecision: StateFlow<AutoBalancer.Decision?> = _lastDecision.asStateFlow()

    init {
        // Lets the service recover from a dead exit without the UI process being alive.
        VoltaVpnService.failoverProvider = { failedId -> planAfterFailure(failedId) }
        // Always-on VPN and sticky restarts reconnect in the user's current mode.
        VoltaVpnService.autoStartProvider = { modeName ->
            modeName?.let { name -> runCatching { ConnectMode.valueOf(name) }.getOrNull() }
                ?.let { prefs.setConnectMode(it) }
            connectWithMode()
        }
        // A node that passed the post-connect check is remembered for its group.
        VoltaVpnService.healthyListener = { id -> rememberHealthy(id) }
    }

    fun hwid(): String = HwidProvider.getHwid(context)
    fun shortHwid(): String = HwidProvider.getShortHwid(context)
    fun coreVersion(): String = com.ars3nb.volta.core.vpn.XrayCore.version()
    fun isCoreAvailable(): Boolean = com.ars3nb.volta.core.vpn.XrayCore.isCoreAvailable

    private val seedMutex = kotlinx.coroutines.sync.Mutex()

    /** Idempotent: the app and the ViewModel both call this on startup. */
    suspend fun ensureSeeded() = seedMutex.withLock {
        withContext(Dispatchers.IO) {
            if (routingDao.count() == 0) {
                RoutingEngine.builtInProfiles().forEach { routingDao.insert(it) }
            }
            if (routingDao.getActive() == null) {
                routingDao.getAll().firstOrNull { it.isBuiltIn }
                    ?.let { routingDao.setActive(it.id) }
            }
            // Group rules change between versions (provider balancers became their own
            // group): re-sort stored nodes now instead of waiting for the next refresh.
            serverDao.getAll().forEach { server ->
                if (server.roleManual) return@forEach
                val role = ServerClassifier.classify(server.remark)
                if (role != server.role) serverDao.updateAutoRole(server.id, role)
            }
        }
    }

    // ------------------------------------------------------------ subscriptions

    suspend fun addSubscription(name: String, url: String): Long = withContext(Dispatchers.IO) {
        val normalized = normalizeSubscriptionUrl(url)
        require(normalized.isNotBlank()) { "Вставьте ссылку на подписку" }
        require(!normalized.startsWith("http://", ignoreCase = true)) {
            "Используйте защищённую HTTPS-ссылку на подписку"
        }
        val id = subDao.insert(
            SubscriptionEntity(
                name = name.trim().ifBlank { "Подписка" },
                nameLocked = name.trim().isNotBlank(),
                url = normalized,
                colorHex = SUBSCRIPTION_COLORS[(subDao.getAll().size) % SUBSCRIPTION_COLORS.size]
            )
        )
        try {
            refreshSubscription(id)
        } catch (e: Exception) {
            subDao.deleteById(id)
            throw e
        }
        id
    }

    suspend fun refreshSubscription(id: Long) = withContext(Dispatchers.IO) {
        val sub = subDao.getById(id) ?: return@withContext
        val fetched = fetchSubscriptionResilient(sub.url, sub.userAgent)
        val body = fetched.body
        val parsed = ConfigParser.parseSubscriptionBody(body, subscriptionId = id)

        val serverName = parsed.title.trim()
        val displayName = if (sub.nameLocked) sub.name else serverName.ifBlank {
            parsed.servers.firstOrNull()?.remark?.takeIf { it.isNotBlank() }
        }.orEmpty().ifBlank { sub.name }

        val servers = ServerClassifier.mergeProviderUpdate(
            previous = serverDao.getBySubscription(id),
            incoming = parsed.servers,
            subscriptionId = id
        )
        if (servers.isEmpty()) {
            val hint = if (parsed.skipped.isNotEmpty()) {
                " В ответе есть только ${parsed.skipped.joinToString(", ")} — этот протокол " +
                    "не поддерживается установленным ядром."
            } else {
                " Проверьте ссылку, авторизацию или формат ответа провайдера."
            }
            error("В ответе подписки не найдено ни одного поддерживаемого сервера.$hint")
        }
        serverDao.replaceSubscription(id, servers)

        if (serverDao.getSelected() == null) {
            serverDao.getBySubscription(id).firstOrNull()?.let { serverDao.selectServer(it.id) }
        }

        // Routing shipped by the provider (routing header, happ://, Happ JSON).
        // A JSON subscription keeps its routing inside every config: that is used as-is
        // at connect time and is deliberately NOT turned into a separate profile.
        val jsonConfigs = looksLikeXrayConfigs(body)
        val routing = HappRoutingParser.parseRoutingLink(fetched.routingHeader)
            ?: if (jsonConfigs) {
                null
            } else {
                HappRoutingParser.extractFromBodyAndHeaders(body = body, routingHeader = "")
                    ?: parsed.routingJson.takeIf { it.isNotBlank() }?.let {
                        HappRoutingParser.extractFromBodyAndHeaders(it, "")
                    }
            }

        val providerRoutingJson = when {
            routing != null -> routing.rawJson.ifBlank { RoutingEngine.serializeRules(routing.rules) }
            jsonConfigs -> ""
            else -> parsed.routingJson
        }

        var boundProfileId = sub.routingProfileId
        if (routing != null && routing.rules.isNotEmpty()) {
            val profileId = applySubscriptionRouting(id, displayName, routing)
            // Auto-bind only when the user has not chosen something else for this
            // subscription; an explicit choice is never overwritten by a refresh.
            if (boundProfileId == 0L) boundProfileId = profileId
        } else if (providerRoutingJson.isBlank()) {
            routingDao.getBySubscription(id)?.let { stale ->
                routingDao.delete(stale)
                if (boundProfileId == stale.id) boundProfileId = 0L
            }
            geoData.delete(id)
        }

        subDao.update(
            sub.copy(
                name = displayName,
                lastUpdated = System.currentTimeMillis(),
                rawBody = "",
                expireAt = parsed.expireAt,
                trafficUsed = parsed.trafficUsed,
                trafficTotal = parsed.trafficTotal,
                announce = parsed.announce,
                supportUrl = parsed.supportUrl,
                profileWebPageUrl = parsed.webPageUrl,
                providerRoutingJson = providerRoutingJson,
                updateIntervalHours = parsed.updateIntervalHours,
                routingProfileId = boundProfileId
            )
        )

        reconcileTunnel()
    }

    /**
     * Stores (or refreshes) the routing set a subscription shipped.
     * The profile is stamped with [RoutingProfileEntity.sourceSubscriptionId], which is
     * what makes it invisible to — and unusable by — every other subscription.
     */
    private suspend fun applySubscriptionRouting(
        subscriptionId: Long,
        displayName: String,
        routing: HappRoutingParser.ParsedRouting
    ): Long {
        val profileName = routing.name.ifBlank { "$displayName · правила" }
        val existing = routingDao.getBySubscription(subscriptionId)

        // A refresh replaces the provider's rules but must not erase what the user added
        // or edited on top of them — those are kept and stay first in the list.
        val userRules = existing
            ?.let { RoutingEngine.parseRules(it.rulesJson) }
            ?.filter { it.source == "user" }
            .orEmpty()
        val mergedRules = (userRules + routing.rules).mapIndexed { i, r -> r.copy(sortOrder = i) }

        val entity = RoutingProfileEntity(
            id = existing?.id ?: 0,
            name = profileName,
            description = "Из подписки «$displayName»",
            isActive = false,
            isBuiltIn = false,
            sourceSubscriptionId = subscriptionId,
            useProviderRules = existing?.useProviderRules ?: true,
            rulesJson = RoutingEngine.serializeRules(mergedRules),
            domainStrategy = routing.domainStrategy.ifBlank { "AsIs" },
            remoteDns = routing.remoteDns.ifBlank {
                existing?.remoteDns ?: "https://1.1.1.1/dns-query"
            },
            domesticDns = routing.domesticDns.ifBlank {
                existing?.domesticDns ?: "https://dns.google/dns-query"
            },
            updatedAt = System.currentTimeMillis(),
            geoipUrl = routing.geoipUrl,
            geositeUrl = routing.geositeUrl,
            geoVersion = routing.geoVersion,
            geoUpdatedAt = existing?.geoUpdatedAt ?: 0,
            dnsHostsJson = routing.dnsHostsJson
        )

        // Happ behaviour: the provider's own geoip/geosite follow the profile and are
        // fetched again whenever it says the lists changed. A failed download keeps the
        // previous files; the next refresh tries again.
        val withGeo = when {
            entity.geoipUrl.isBlank() && entity.geositeUrl.isBlank() -> {
                geoData.delete(subscriptionId)
                entity.copy(geoUpdatedAt = 0)
            }

            geoData.isStale(subscriptionId, entity, existing) &&
                (geoData.download(subscriptionId, entity) ||
                    (isConnected() && geoData.download(subscriptionId, entity, tunnelHttp))) ->
                entity.copy(geoUpdatedAt = System.currentTimeMillis())

            else -> entity
        }

        return if (existing != null) {
            routingDao.update(withGeo)
            existing.id
        } else {
            routingDao.insert(withGeo)
        }
    }

    /** A body made of full Xray configs (v2rayNG-style JSON subscription). */
    private fun looksLikeXrayConfigs(body: String): Boolean {
        val payload = body.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
            .trim()
        return (payload.startsWith("[") || payload.startsWith("{")) &&
            payload.contains("\"outbounds\"")
    }

    suspend fun deleteSubscription(id: Long) = withContext(Dispatchers.IO) {
        routingDao.getBySubscription(id)?.let { subDao.unbindRoutingProfile(it.id) }
        serverDao.deleteBySubscription(id)
        routingDao.deleteBySubscription(id)
        geoData.delete(id)
        subDao.deleteById(id)
        if (serverDao.getSelected() == null) {
            serverDao.getAll().firstOrNull()?.let { serverDao.selectServer(it.id) }
        }
        reconcileTunnel()
    }

    suspend fun updateSubscription(id: Long, name: String, url: String) =
        withContext(Dispatchers.IO) {
            val current = subDao.getById(id) ?: error("Подписка не найдена")
            val normalized = normalizeSubscriptionUrl(url)
            require(normalized.isNotBlank()) { "Вставьте ссылку на подписку" }
            require(!normalized.startsWith("http://", ignoreCase = true)) {
                "Используйте защищённую HTTPS-ссылку на подписку"
            }
            val updated = current.copy(
                name = name.trim().ifBlank { current.name },
                nameLocked = name.trim().isNotBlank(),
                url = normalized
            )
            subDao.update(updated)
            if (updated.url != current.url) {
                try {
                    refreshSubscription(id)
                } catch (e: Exception) {
                    subDao.update(current)
                    throw e
                }
            }
        }

    suspend fun setSubscriptionEnabled(id: Long, enabled: Boolean) =
        subDao.setEnabled(id, enabled)

    suspend fun refreshAllSubscriptions(): Int = withContext(Dispatchers.IO) {
        val subs = subDao.getAll().filter { it.isEnabled && it.url.startsWith("http") }
        var ok = 0
        for (sub in subs) {
            if (runCatching { refreshSubscription(sub.id) }.isSuccess) ok++
        }
        ok
    }

    suspend fun importShareLink(link: String): ServerEntity? = withContext(Dispatchers.IO) {
        val parsed = ConfigParser.parseShareLink(link.trim()) ?: return@withContext null
        val server = ServerClassifier.classify(parsed)
        val id = serverDao.insert(server)
        if (serverDao.getSelected() == null) serverDao.selectServer(id)
        server.copy(id = id)
    }

    suspend fun importJson(json: String): Int = withContext(Dispatchers.IO) {
        val parsed = ConfigParser.parseSubscriptionBody(json)
        parsed.servers.map(ServerClassifier::classify).forEach { serverDao.insert(it) }
        if (serverDao.getSelected() == null) {
            serverDao.getAll().firstOrNull()?.let { serverDao.selectServer(it.id) }
        }
        parsed.servers.size
    }

    // ----------------------------------------------------------------- servers

    suspend fun toggleFavorite(id: Long) {
        val s = serverDao.getById(id) ?: return
        serverDao.setFavorite(id, !s.isFavorite)
    }

    /**
     * Selecting a server while the tunnel is up rebuilds it, otherwise the UI and the
     * notification would name a server that carries no traffic.
     */
    suspend fun selectServer(id: Long) = withContext(Dispatchers.IO) {
        serverDao.selectServer(id)
        if (isConnected() && VoltaVpnService.serverId.value != id) {
            runCatching { connectSelected() }
        }
    }

    /** Keeps the tunnel honest after the library changed under it. */
    private suspend fun reconcileTunnel() {
        if (!isConnected()) return
        val liveId = VoltaVpnService.serverId.value
        if (liveId == 0L) return
        val stillThere = serverDao.getById(liveId) != null
        if (stillThere) return
        // The node the tunnel runs on is gone: move to the next best or shut down.
        val replacement = serverDao.getAll().firstOrNull { ConfigBuilder.canDial(it) }
        if (replacement == null) {
            disconnect()
        } else {
            serverDao.selectServer(replacement.id)
            runCatching { connectSelected() }.onFailure { disconnect() }
        }
    }

    suspend fun deleteServer(id: Long) = withContext(Dispatchers.IO) {
        val server = serverDao.getById(id) ?: return@withContext
        serverDao.deleteById(id)
        if (server.isSelected) {
            serverDao.getAll().firstOrNull()?.let { serverDao.selectServer(it.id) }
        }
        reconcileTunnel()
    }

    suspend fun updateServerRole(id: Long, role: ServerRole) = serverDao.updateRole(id, role)

    suspend fun pingServer(id: Long): PingResult = withContext(Dispatchers.IO) {
        val server = serverDao.getById(id) ?: error("Сервер не найден")
        val engine = PingEngine(context, batterySaver = prefs.batterySaver.first())
        val result = engine.pingServer(server, prefs.defaultPingType.first())
        persist(server, result)
        result
    }

    suspend fun pingAll(type: PingType? = null, pool: ServerPool = ServerPool.ALL): Int =
        withContext(Dispatchers.IO) {
            val engine = PingEngine(context, batterySaver = prefs.batterySaver.first())
            val pingType = type ?: prefs.defaultPingType.first()
            val all = serverDao.getAll().filter { pool.matches(it.role) }
            if (all.isEmpty()) return@withContext 0
            val results = engine.pingServers(all, pingType)
            val byId = all.associateBy { it.id }
            results.forEach { (id, r) -> byId[id]?.let { persist(it, r) } }
            results.count { it.second.success }
        }

    /** Writes a measurement back: raw value, smoothed value and the failure streak. */
    private suspend fun persist(server: ServerEntity, result: PingResult) {
        val ok = result.success && result.latencyMs >= 0
        serverDao.updateHealth(
            id = server.id,
            latency = if (ok) result.latencyMs else -1,
            ewma = if (ok) AutoBalancer.blendEwma(server.ewmaMs, result.latencyMs) else server.ewmaMs,
            failStreak = if (ok) 0 else (server.failStreak + 1).coerceAtMost(9)
        )
    }

    // ---------------------------------------------------------------- routing

    /**
     * Resolves which routing profile a server must run with.
     *
     * Isolation rules, in order:
     *  1. a subscription may bind any app-level profile, or its own provider profile;
     *  2. a profile owned by subscription A is never handed to subscription B,
     *     even if a stale binding points at it;
     *  3. provider rule JSON is only ever read from the server's own subscription,
     *     and only when the active profile is that subscription's provider profile.
     */
    private data class RoutingChoice(
        val profile: RoutingProfileEntity?,
        val providerJson: String,
        val geo: GeoNames?,
        val preferServerRouting: Boolean
    )

    private suspend fun routingFor(server: ServerEntity): RoutingChoice {
        val sub = server.subscriptionId?.let { subDao.getById(it) }
        val profiles = routingDao.getAll()
        val profile = RoutingResolver.resolve(sub, profiles)
        // Geodata is private to its subscription: only that subscription's own profile
        // may point its rules at the downloaded files.
        val geo = if (sub != null && profile?.sourceSubscriptionId == sub.id) {
            geoData.installed(sub.id)
        } else null
        val explicitAppProfile = sub != null && sub.routingProfileId > 0 &&
            profiles.firstOrNull { it.id == sub.routingProfileId }?.sourceSubscriptionId == null
        return RoutingChoice(
            profile = profile,
            providerJson = RoutingResolver.providerJsonFor(sub, profile),
            geo = geo,
            preferServerRouting = sub != null && !explicitAppProfile
        )
    }

    /** The profile that would actually be used for this subscription right now. */
    suspend fun effectiveRoutingProfile(subscriptionId: Long?): RoutingProfileEntity? =
        withContext(Dispatchers.IO) {
            RoutingResolver.resolve(subscriptionId?.let { subDao.getById(it) }, routingDao.getAll())
        }

    /** Binds a routing profile to one subscription; rejects another subscription's profile. */
    suspend fun bindSubscriptionRouting(subscriptionId: Long, profileId: Long) =
        withContext(Dispatchers.IO) {
            if (profileId == 0L) {
                subDao.bindRoutingProfile(subscriptionId, 0)
                return@withContext
            }
            val profile = routingDao.getById(profileId) ?: error("Набор правил не найден")
            require(
                profile.sourceSubscriptionId == null ||
                    profile.sourceSubscriptionId == subscriptionId
            ) {
                "Эти правила принадлежат другой подписке и не могут быть применены здесь"
            }
            subDao.bindRoutingProfile(subscriptionId, profileId)
        }

    suspend fun setActiveRouting(id: Long) = routingDao.setActive(id)

    suspend fun saveRoutingProfile(profile: RoutingProfileEntity): Long =
        if (profile.id == 0L) routingDao.insert(profile)
        else {
            routingDao.update(profile)
            profile.id
        }

    suspend fun updateRoutingRules(profileId: Long, rules: List<RoutingRuleDto>) {
        val p = routingDao.getById(profileId) ?: return
        routingDao.update(
            p.copy(
                rulesJson = RoutingEngine.serializeRules(rules),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setRoutingProviderRules(profileId: Long, enabled: Boolean) {
        val profile = routingDao.getById(profileId) ?: return
        routingDao.update(
            profile.copy(useProviderRules = enabled, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun setRoutingDns(profileId: Long, remote: String, domestic: String) {
        val profile = routingDao.getById(profileId) ?: return
        routingDao.update(
            profile.copy(
                remoteDns = remote.trim(),
                domesticDns = domestic.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteRoutingProfile(profile: RoutingProfileEntity) = withContext(Dispatchers.IO) {
        if (profile.isBuiltIn) return@withContext
        subDao.unbindRoutingProfile(profile.id)
        routingDao.delete(profile)
        if (routingDao.getActive() == null) {
            routingDao.getAll().firstOrNull { it.isBuiltIn }?.let { routingDao.setActive(it.id) }
        }
    }

    /** Detaches a copy from its subscription so the user can edit it freely. */
    suspend fun copyRoutingProfile(profileId: Long): Long = withContext(Dispatchers.IO) {
        val source = routingDao.getById(profileId) ?: error("Набор правил не найден")
        routingDao.insert(
            source.copy(
                id = 0,
                name = "${source.name} · копия",
                description = "Свои правила на основе «${source.name}»",
                isActive = false,
                isBuiltIn = false,
                sourceSubscriptionId = null,
                useProviderRules = false,
                rulesJson = RoutingEngine.serializeRules(
                    RoutingEngine.parseRules(source.rulesJson).map { it.copy(source = "user") }
                ),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // --------------------------------------------------------------- network

    suspend fun probeNetwork(): Pair<NetworkRegime, List<SiteProbeResult>> =
        withContext(Dispatchers.IO) {
            val outcome = NetworkProbe(prefs.batterySaver.first()).run()
            prefs.setNetworkRegime(outcome.regime.name)
            rememberNetwork(outcome)
            outcome.regime to outcome.probes
        }

    /** The last network check, valid while the phone stays on the same network. */
    private data class NetworkMemo(val key: String, val outcome: NetworkProbe.Outcome, val at: Long)

    @Volatile
    private var networkMemo: NetworkMemo? = null

    /**
     * Identity of the physical network this app sees (it is outside its own VPN).
     * Android gives every new Wi-Fi association or mobile session a fresh id, so a
     * changed network never inherits an old answer.
     */
    private fun networkKey(): String? = runCatching {
        context.getSystemService(android.net.ConnectivityManager::class.java)
            ?.activeNetwork?.toString()
    }.getOrNull()

    private fun rememberNetwork(outcome: NetworkProbe.Outcome) {
        val key = networkKey() ?: return
        networkMemo = if (outcome.hasAnyNetwork) {
            NetworkMemo(key, outcome, System.currentTimeMillis())
        } else {
            null
        }
    }

    private fun knownNetwork(): NetworkProbe.Outcome? {
        val memo = networkMemo ?: return null
        val fresh = System.currentTimeMillis() - memo.at < NETWORK_MEMO_TTL_MS
        return memo.outcome.takeIf { fresh && memo.key == networkKey() }
    }

    /** Forget the network answer, e.g. when Wi-Fi ↔ mobile data changed under us. */
    fun forgetNetwork() {
        networkMemo = null
    }

    /**
     * Runs one of the three user-facing autobalancers.
     *
     * [BalancerScope.ALL] probes the network first and refuses to measure anything when
     * there is no connectivity at all — the caller is told to retry later instead of
     * every server being marked dead.
     */
    suspend fun runAutobalancer(
        scope: BalancerScope = BalancerScope.ALL,
        onPhase: (AutoBalancer.Phase) -> Unit = {},
        quick: Boolean = false,
        instant: Boolean = true,
        /** A node that just failed: not a candidate, and replaced only by one that answered. */
        exclude: Long? = null,
        /**
         * Reuse the site check made moments ago instead of probing again. Every connect
         * in Home+LTE (button, tiles, boot) probes the sites afresh.
         */
        reuseNetwork: Boolean = false
    ): AutoBalancer.Decision = withContext(Dispatchers.IO) {
        val battery = prefs.batterySaver.first()
        val balancer = AutoBalancer(PingEngine(context, batterySaver = battery), NetworkProbe(battery))
        val current = serverDao.getSelected()
        val remembered = buildMap {
            prefs.lastGoodHome.first().takeIf { it > 0 }?.let { put(BalancerScope.NORMAL, it) }
            prefs.lastGoodLte.first().takeIf { it > 0 }?.let { put(BalancerScope.BYPASS, it) }
        }
        val decision = balancer.choose(
            servers = serverDao.getAll().filter { it.id != exclude },
            scope = scope,
            current = current,
            pingType = prefs.defaultPingType.first(),
            onPhase = onPhase,
            remembered = remembered,
            quick = quick,
            knownNetwork = if (reuseNetwork) knownNetwork() else null,
            instant = instant
        )

        decision.ranked.forEach { ranked ->
            serverDao.updateHealth(
                id = ranked.server.id,
                latency = ranked.latencyMs,
                ewma = ranked.ewmaMs,
                failStreak = if (ranked.ok) 0 else (ranked.server.failStreak + 1).coerceAtMost(9)
            )
        }
        if (decision.probes.isNotEmpty()) {
            prefs.setNetworkRegime(decision.regime.name)
            if (scope == BalancerScope.ALL) {
                val homeOk = decision.probes.count { it.category == SiteCategory.FOREIGN && it.reachable }
                val lteOk = decision.probes.count { it.category == SiteCategory.RU_WHITELIST && it.reachable }
                rememberNetwork(
                    NetworkProbe.Outcome(
                        regime = decision.regime,
                        probes = decision.probes,
                        foreignOk = homeOk,
                        foreignTotal = NetworkProbe.HOME_HOSTS.size,
                        localOk = lteOk,
                        localTotal = NetworkProbe.LTE_HOSTS.size
                    )
                )
            }
        }
        if (decision.ranked.isNotEmpty()) _lastDecision.value = decision
        // Leaving a failed node for one nobody measured would only trade one guess for
        // another; better to stay until something answers.
        val next = decision.selected?.takeIf { pick ->
            exclude == null || decision.ranked.any { it.ok && it.server.id == pick.id }
        }
        next?.let { if (it.id != current?.id) selectServer(it.id) }
        decision
    }

    /** While connected the lookup goes through the tunnel, so it shows the exit IP. */
    suspend fun fetchIp(): IpInfo =
        IpChecker.fetch(socksPort = if (isConnected()) LOCAL_SOCKS_PORT else null)

    // ------------------------------------------------------------ connection

    /**
     * Everything needed to raise the tunnel for the currently selected server.
     * @param excludeServerId node that has just failed and must not be chosen again.
     */
    suspend fun buildConnectionPlan(
        excludeServerId: Long? = null
    ): VoltaVpnService.ConnectionPlan = withContext(Dispatchers.IO) {
        // Without the native core a "connection" would be a lie: the TUN would come up
        // with nothing behind it and the app would claim CONNECTED.
        check(isCoreAvailable()) {
            "Ядро VPN не установлено в этой сборке — подключение невозможно"
        }
        val all = serverDao.getAll()
        require(all.isNotEmpty()) { "Сначала добавьте подписку или сервер" }

        var server = serverDao.getSelected()
        if (server == null || server.id == excludeServerId) {
            val dialable = all.filter { it.id != excludeServerId && ConfigBuilder.canDial(it) }
            // A provider balancer only when nothing else is left.
            server = (AutoBalancer.poolFor(dialable, BalancerScope.ALL).ifEmpty { dialable })
                .minByOrNull { it.score }
                ?: error("Нет доступных серверов для подключения")
            serverDao.selectServer(server.id)
        }
        ConfigBuilder.dialProblem(server)?.let { error(it) }

        val routing = routingFor(server)
        val config = ConfigBuilder.build(
            server = CertPins.apply(server),
            profile = routing.profile,
            providerRoutingJson = routing.providerJson,
            batterySaver = prefs.batterySaver.first(),
            localDns = prefs.localDns.first(),
            geo = routing.geo,
            preferServerRouting = routing.preferServerRouting
        )

        VoltaVpnService.ConnectionPlan(
            serverId = server.id,
            serverName = server.remark,
            configJson = config,
            mtu = prefs.mtu.first(),
            perAppMode = prefs.perAppMode.first(),
            perAppPackages = prefs.perAppPackages.first(),
            openFluxLink = if (server.protocol == ProtocolType.OPENFLUX) server.rawLink else ""
        )
    }

    /** Picks a different node after [failedServerId] died, then rebuilds the plan. */
    private suspend fun planAfterFailure(failedServerId: Long): VoltaVpnService.ConnectionPlan? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!prefs.autoFailover.first()) return@runCatching null
                serverDao.getById(failedServerId)?.let { failed ->
                    serverDao.updateHealth(
                        id = failed.id,
                        latency = -1,
                        ewma = failed.ewmaMs,
                        failStreak = (failed.failStreak + 1).coerceAtMost(9)
                    )
                }
                val candidates = serverDao.getAll()
                    .filter { it.id != failedServerId && ConfigBuilder.canDial(it) }
                // Stay inside the group the user asked for: an LTE exit is no use on
                // Wi-Fi and a Home exit dies on a whitelisted mobile network.
                val next = groupFor(candidates).minByOrNull { it.score }
                    ?: AutoBalancer.poolFor(candidates, BalancerScope.ALL).minByOrNull { it.score }
                    ?: return@runCatching null
                serverDao.selectServer(next.id)
                buildConnectionPlan(excludeServerId = failedServerId)
            }.getOrNull()
        }

    suspend fun connectSelected() = withContext(Dispatchers.IO) {
        val plan = buildConnectionPlan()
        VoltaVpnService.connect(context, plan)
    }

    /** Servers of the group the current connect mode works with. */
    private suspend fun groupFor(servers: List<ServerEntity>): List<ServerEntity> =
        when (prefs.connectMode.first()) {
            ConnectMode.AUTO_HOME -> AutoBalancer.poolFor(servers, BalancerScope.NORMAL)
            ConnectMode.AUTO_LTE -> AutoBalancer.poolFor(servers, BalancerScope.BYPASS)
            ConnectMode.AUTO_HOME_LTE -> AutoBalancer.filterByRegime(
                servers,
                runCatching { NetworkRegime.valueOf(prefs.networkRegime.first()) }
                    .getOrDefault(NetworkRegime.UNKNOWN)
            )

            ConnectMode.MANUAL -> servers
        }

    /**
     * The big button. Auto modes first pick the best node of their group (Home+LTE
     * probes the network to learn which group that is), then raise the tunnel on it.
     * A run that measured nothing still connects to the best node known so far: the
     * probe can be wrong, the tunnel attempt tells the truth.
     */
    suspend fun connectWithMode(
        onPhase: (AutoBalancer.Phase) -> Unit = {}
    ): AutoBalancer.Decision? = withContext(Dispatchers.IO) {
        connectMutex.withLock {
            _autoPicking.value = true
            try {
                connectWithModeLocked { phase ->
                    _pickPhase.value = phase
                    onPhase(phase)
                }
            } finally {
                _autoPicking.value = false
                _pickPhase.value = null
            }
        }
    }

    /**
     * The tunnel on [serverId] answered its first health check: clear its failure
     * streak and remember it as the node to start from next time in its group.
     */
    private suspend fun rememberHealthy(serverId: Long) = withContext(Dispatchers.IO) {
        val server = serverDao.getById(serverId) ?: return@withContext
        if (server.failStreak != 0) {
            serverDao.updateHealth(
                id = server.id,
                latency = server.latencyMs,
                ewma = server.ewmaMs,
                failStreak = 0
            )
        }
        // Remembered per group; provider balancers belong to none.
        when (server.role) {
            ServerRole.NORMAL -> prefs.setLastGood(home = true, serverId = server.id)
            ServerRole.BYPASS_WHITELIST -> prefs.setLastGood(home = false, serverId = server.id)
            ServerRole.AUTO -> Unit
        }
    }

    private suspend fun connectWithModeLocked(
        onPhase: (AutoBalancer.Phase) -> Unit
    ): AutoBalancer.Decision? {
        val scope = prefs.connectMode.first().scope
        // Connect-time pick is the quick one: last good node or a short race, so the
        // tunnel is up in seconds. The thorough comparison follows in the background.
        val decision = scope?.let { runAutobalancer(it, onPhase, quick = true) }
        if (decision != null && decision.selected == null) {
            error(decision.reason)
        }
        // A live tunnel already on the chosen node stays as it is; a switch on a live
        // tunnel was already started by selectServer() and the service drops the repeat.
        val liveOnSelected = isConnected() &&
            VoltaVpnService.serverId.value == serverDao.getSelected()?.id
        if (!liveOnSelected) connectSelected()
        if (decision?.quick == true) scheduleRefine()
        return decision
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refineJob: Job? = null

    /**
     * After a quick connect, the full comparison (every node, finalists re-measured)
     * once the tunnel has settled; a clearly better node then takes over.
     */
    private fun scheduleRefine() {
        refineJob?.cancel()
        refineJob = background.launch {
            delay(REFINE_AFTER_CONNECT_MS)
            if (isConnected()) runCatching { reevaluateAuto() }
        }
    }

    /** Re-run the auto choice on a live tunnel, e.g. after Wi-Fi ↔ mobile data. */
    suspend fun reevaluateAuto(): AutoBalancer.Decision? = withContext(Dispatchers.IO) {
        // Same lock as a connect: a background re-check must not race the button.
        connectMutex.withLock {
            if (!isConnected()) return@withLock null
            val scope = prefs.connectMode.first().scope ?: return@withLock null
            // runAutobalancer → selectServer reconnects by itself when the node changes.
            runAutobalancer(scope)
        }
    }

    // ------------------------------------------------------------ guard

    /**
     * One background check on a live tunnel, every two minutes while the screen is on
     * (ConnectionGuard). In Home+LTE the sites go first: they are cheap and say whether
     * the group in use still fits this network. Then the node in use is pinged directly;
     * the group is measured only when the network moved or that node stopped answering.
     * Everything here runs outside the tunnel (this app is excluded from its own VPN),
     * so a dead server can't hide behind a working tunnel.
     */
    suspend fun guardCheck(full: Boolean) = withContext(Dispatchers.IO) {
        if (!isConnected() || _autoPicking.value) return@withContext
        val live = serverDao.getById(VoltaVpnService.serverId.value) ?: return@withContext
        val mode = prefs.connectMode.first()
        val battery = prefs.batterySaver.first()
        val probe = NetworkProbe(battery)

        if (mode == ConnectMode.AUTO_HOME_LTE) {
            var network = probe.runFast()
            // No site answers: there is no internet right now, and that is not the
            // server's fault. Nothing to fix until the network is back.
            if (!network.hasAnyNetwork) return@withContext
            if (prefs.followNetwork.first()) {
                val dialable = serverDao.getAll().filter { ConfigBuilder.canDial(it) }
                val fits = { outcome: NetworkProbe.Outcome ->
                    AutoBalancer.filterByRegime(dialable, outcome.regime).any { it.id == live.id }
                }
                if (!fits(network)) {
                    // The quick check stops at the first answer; before moving to the
                    // other group, hear every site out so one slow host can't flip it.
                    network = probe.run()
                    if (!network.hasAnyNetwork) return@withContext
                    saveNetwork(network)
                    if (!fits(network)) {
                        moveOff(live, BalancerScope.ALL, failed = false)
                        return@withContext
                    }
                }
            }
            saveNetwork(network)
        }

        val answer = PingEngine(context, batterySaver = battery).stillAnswers(live)
        if (answer.success) {
            persist(live, answer)
            if (full) reevaluateAuto()
            return@withContext
        }

        // Other modes don't watch the sites; ask them only now, to be sure it is the
        // server that went quiet and not the internet.
        if (mode != ConnectMode.AUTO_HOME_LTE && !probe.runFast().hasAnyNetwork) return@withContext
        // The sites answered and the server didn't: the server is the problem.
        val scope = mode.scope
        if (scope != null) {
            moveOff(live, scope, failed = true)
        } else if (prefs.autoFailover.first()) {
            connectMutex.withLock {
                if (!isConnected() || VoltaVpnService.serverId.value != live.id) return@withLock
                planAfterFailure(live.id)?.let { VoltaVpnService.connect(context, it) }
            }
        }
    }

    private suspend fun saveNetwork(outcome: NetworkProbe.Outcome) {
        prefs.setNetworkRegime(outcome.regime.name)
        rememberNetwork(outcome)
    }

    /**
     * Takes a live tunnel off [from]: the group is measured (the network check just
     * made is reused) and the tunnel moves to the best node that answered.
     */
    private suspend fun moveOff(from: ServerEntity, scope: BalancerScope, failed: Boolean) {
        connectMutex.withLock {
            // The button, a tile or the service's own failover got there first.
            if (!isConnected() || VoltaVpnService.serverId.value != from.id) return@withLock
            if (failed) persist(from, PingResult(from.remark, PingType.REAL_DELAY, -1, false))
            val decision = runAutobalancer(
                scope,
                quick = true,
                instant = false,
                exclude = if (failed) from.id else null,
                reuseNetwork = true
            )
            // runAutobalancer → selectServer has already started the move.
            if (decision.selected != null && serverDao.getSelected()?.id != from.id) scheduleRefine()
        }
    }

    suspend fun setConnectMode(mode: ConnectMode) = prefs.setConnectMode(mode)
    suspend fun setRefreshOnLaunch(v: Boolean) = prefs.setRefreshOnLaunch(v)
    suspend fun setFollowNetwork(v: Boolean) = prefs.setFollowNetwork(v)
    suspend fun setAutoRecheck(v: Boolean) = prefs.setAutoRecheck(v)
    suspend fun setHideBalancers(v: Boolean) = prefs.setHideBalancers(v)


    fun disconnect() = VoltaVpnService.disconnect(context)

    // ------------------------------------------------------------- fetching

    private data class FetchedSubscription(val body: String, val routingHeader: String = "")

    private fun normalizeSubscriptionUrl(url: String): String {
        var value = url.trim().removePrefix("\uFEFF")
        if (value.startsWith("<") && value.endsWith(">") && value.length > 2) {
            value = value.substring(1, value.lastIndex).trim()
        }
        return value
    }

    /** Direct first; if that fails while the VPN is up, once more through the tunnel. */
    private suspend fun fetchSubscriptionResilient(url: String, userAgent: String): FetchedSubscription =
        try {
            fetchSubscription(url, userAgent, http)
        } catch (direct: Exception) {
            if (!isConnected()) throw direct
            runCatching { fetchSubscription(url, userAgent, tunnelHttp) }.getOrElse { throw direct }
        }

    private suspend fun fetchSubscription(
        url: String,
        userAgent: String,
        client: OkHttpClient
    ): FetchedSubscription {
        val normalizedUrl = normalizeSubscriptionUrl(url)
        // Pasted content rather than a URL.
        if (normalizedUrl.startsWith("data:") ||
            normalizedUrl.contains(
                Regex("(?i)(vless|vmess|trojan|ss|socks5?|hysteria2|hy2|wireguard)://")
            ) ||
            normalizedUrl.startsWith("{") || normalizedUrl.startsWith("[") ||
            normalizedUrl.contains("\n")
        ) {
            return FetchedSubscription(normalizedUrl.removePrefix("data:"))
        }

        val builder = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", userAgent.trim().ifBlank { "Volta/1.0 (Android)" })
            .header("x-device-os", "Android")
            .header("x-ver-os", android.os.Build.VERSION.RELEASE)
            .header("x-device-model", android.os.Build.MODEL)
            .header("x-app-version", "1.0.0")
            .get()

        if (prefs.sendHwid.first()) {
            builder.header("x-hwid", HwidProvider.getHwid(context))
        }

        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (e: UnknownHostException) {
            error("Нет связи с сервером подписки. Проверьте интернет.")
        } catch (e: SocketTimeoutException) {
            error("Сервер подписки не ответил вовремя.")
        } catch (e: SSLException) {
            error("Не удалось установить защищённое соединение с сервером подписки.")
        } catch (e: IOException) {
            error("Ошибка сети при загрузке подписки: ${e.message ?: e.javaClass.simpleName}")
        }

        resp.use {
            if (!it.isSuccessful) error("Не удалось скачать подписку (код ${it.code})")
            val body = it.body?.string().orEmpty().removePrefix("\uFEFF")
            if (body.isBlank()) error("Подписка пустая")

            val title = headerIgnoreCase(it, "profile-title")
                .ifBlank {
                    headerIgnoreCase(it, "content-disposition").let(::titleFromContentDisposition)
                }
            val headerInfo = headerIgnoreCase(it, "subscription-userinfo")
            val routingHeader = headerIgnoreCase(it, "routing")
            val extra = buildString {
                if (title.isNotBlank()) appendLine("#profile-title: $title")
                if (headerInfo.isNotBlank()) appendLine("#subscription-userinfo: $headerInfo")
                if (routingHeader.isNotBlank()) appendLine("#routing-link: $routingHeader")
                headerIgnoreCase(it, "support-url").takeIf { v -> v.isNotBlank() }?.let { v ->
                    appendLine("#support-url: $v")
                }
                headerIgnoreCase(it, "announce").takeIf { v -> v.isNotBlank() }?.let { v ->
                    appendLine("#announce: $v")
                }
                headerIgnoreCase(it, "profile-update-interval").takeIf { v -> v.isNotBlank() }
                    ?.let { v -> appendLine("#profile-update-interval: $v") }
                headerIgnoreCase(it, "profile-web-page-url").takeIf { v -> v.isNotBlank() }
                    ?.let { v -> appendLine("#profile-web-page-url: $v") }
            }
            return FetchedSubscription(body = extra + body, routingHeader = routingHeader)
        }
    }

    private fun headerIgnoreCase(resp: Response, name: String): String {
        resp.headers.names().forEach { n ->
            if (n.equals(name, ignoreCase = true)) return resp.header(n).orEmpty()
        }
        return ""
    }

    private fun titleFromContentDisposition(cd: String): String {
        if (cd.isBlank()) return ""
        val m = Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""", RegexOption.IGNORE_CASE)
            .find(cd) ?: return ""
        return ConfigParser.decodeProfileTitle(m.groupValues[1].trim().trim('"'))
    }

    // ------------------------------------------------------------ preferences

    suspend fun setDesignTheme(t: DesignTheme) = prefs.setDesignTheme(t)
    suspend fun setPerAppMode(mode: PerAppMode) = prefs.setPerAppMode(mode)
    suspend fun setPerAppPackages(packages: Set<String>) = prefs.setPerAppPackages(packages)
    suspend fun setAutoFailover(v: Boolean) = prefs.setAutoFailover(v)
    suspend fun setPingOnStart(v: Boolean) = prefs.setPingOnStart(v)
    suspend fun setDefaultPingType(t: PingType) = prefs.setDefaultPingType(t)
    suspend fun setBatterySaver(v: Boolean) = prefs.setBatterySaver(v)
    suspend fun setSendHwid(v: Boolean) = prefs.setSendHwid(v)
    suspend fun setAutoConnect(v: Boolean) = prefs.setAutoConnect(v)
    suspend fun setLocalDns(v: Boolean) = prefs.setLocalDns(v)
    suspend fun setMtu(v: Int) = prefs.setMtu(v)

    fun isConnected(): Boolean = vpnState.value == VpnConnectionState.CONNECTED

    companion object {
        /** Must match the port ConfigBuilder gives the socks inbound. */
        private const val LOCAL_SOCKS_PORT = 10808

        /** Must match the port ConfigBuilder gives the http inbound. */
        const val LOCAL_HTTP_PORT = 10809

        /** A network check stays good this long on the same network. */
        private const val NETWORK_MEMO_TTL_MS = 10 * 60_000L

        /** After a quick connect: past the health check, before anyone notices. */
        private const val REFINE_AFTER_CONNECT_MS = 20_000L

        val SUBSCRIPTION_COLORS = listOf(
            "#C4784A", "#4A8FC4", "#5FB07A", "#B05FA8", "#C4A24A", "#4AC4B8"
        )
    }
}
