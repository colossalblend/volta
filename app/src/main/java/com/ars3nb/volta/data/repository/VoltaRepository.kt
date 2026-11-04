package com.ars3nb.volta.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.core.HwidProvider
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.balancer.GuardPolicy
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.locale.UiTextException
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.server.ServerMarkup
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
import com.ars3nb.volta.data.model.AdBlockMode
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
import com.ars3nb.volta.data.model.TrafficDayEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class VoltaRepository(private val context: Context) {

    /** Stops with a message the screen shows in its own language (see [UiTextException]). */
    private fun fail(@StringRes id: Int, vararg args: Any): Nothing =
        throw UiTextException(UiText.Res(id, args.toList()), context)

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

    /** The node the tunnel is actually running on (changes only once a switch is done). */
    val liveServerId: StateFlow<Long> = VoltaVpnService.serverId
    val vpnError = VoltaVpnService.error
    val traffic = VoltaVpnService.traffic
    val connectedSince = VoltaVpnService.connectedSince

    /** Always-on VPN and its lockdown, as the running tunnel last saw them. */
    val alwaysOn = VoltaVpnService.alwaysOn

    /** Bytes through the VPN server per day from [fromDay] on (see TrafficRecorder). */
    fun trafficSince(fromDay: Long): Flow<List<TrafficDayEntity>> = db.trafficDao().observeSince(fromDay)

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
    val adBlock = prefs.adBlock
    val blockTorrents = prefs.blockTorrents
    val connectMode = prefs.connectMode
    val refreshOnLaunch = prefs.refreshOnLaunch
    val followNetwork = prefs.followNetwork
    val autoRecheck = prefs.autoRecheck
    val hideBalancers = prefs.hideBalancers
    val simpleMode = prefs.simpleMode
    val lastRegime = prefs.networkRegime

    /**
     * Whether any LTE server is there to switch to. Without one, "Auto LTE" and
     * "Auto Home+LTE" run as Home (see [GuardPolicy.effectiveMode]) and no site is probed.
     */
    val hasLteServers: Flow<Boolean> = servers.map(GuardPolicy::hasLte).distinctUntilChanged()

    /** The stored mode, corrected for a library without LTE servers. */
    private suspend fun effectiveMode(): ConnectMode =
        GuardPolicy.effectiveMode(prefs.connectMode.first(), GuardPolicy.hasLte(serverDao.getAll()))

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
            retireOldBuiltIns()
            if (routingDao.getActive() == null) {
                routingDao.getAll().firstOrNull { it.isBuiltIn }
                    ?.let { routingDao.setActive(it.id) }
            }
            // Group rules change between versions (provider balancers became their own
            // group): re-sort stored nodes now instead of waiting for the next refresh.
            // A role the provider gave is left alone, and a group it marked stays complete.
            val typesBySub = subDao.getAll().associate { it.id to ServerMarkup.parseServerTypes(it.serverTypes) }
            serverDao.getAll().groupBy { it.subscriptionId }.forEach { (subId, group) ->
                val explicit = ServerClassifier.explicitGroups(
                    group, typesBySub[subId] ?: ServerMarkup.TypeMap.EMPTY
                )
                group.forEach { server ->
                    if (server.roleManual || server.roleFromProvider) return@forEach
                    val role = ServerClassifier.autoRole(server, explicit)
                    if (role != server.role) serverDao.updateAutoRole(server.id, role)
                }
            }
        }
    }

    /**
     * "Без рекламы" and "Максимальная маскировка" became the AdGuard and torrent filters.
     * Their users keep what they had: the filter goes on, the set itself goes away.
     */
    private suspend fun retireOldBuiltIns() {
        val retired = routingDao.getAll().filter {
            it.isBuiltIn && it.name in setOf(RoutingEngine.RETIRED_NO_ADS, RoutingEngine.RETIRED_STEALTH)
        }
        if (retired.isEmpty()) return
        val boundIds = subDao.getAll().map { it.routingProfileId }.toSet()
        retired.forEach { profile ->
            if (profile.isActive || profile.id in boundIds) {
                when (profile.name) {
                    RoutingEngine.RETIRED_NO_ADS -> prefs.setAdBlock(AdBlockMode.STANDARD)
                    RoutingEngine.RETIRED_STEALTH -> prefs.setBlockTorrents(true)
                }
            }
            subDao.unbindRoutingProfile(profile.id)
            routingDao.delete(profile)
        }
        if (routingDao.getActive() == null) {
            routingDao.getAll().firstOrNull { it.isBuiltIn }?.let { routingDao.setActive(it.id) }
        }
    }

    // ------------------------------------------------------------ subscriptions

    /**
     * Adds a subscription, or updates the one already holding this address (an import
     * link opened twice must not double every server).
     *
     * @param hidden the provider keeps the link private (`visible_subs=false` or an
     *   encrypted link): the app will never show or edit it.
     * @param reveal the link said `visible_subs=true` outright, so a stored hidden one
     *   with the same address may become visible again.
     * @param serverTypes `server-type` markup from the link; empty keeps what was stored.
     */
    suspend fun addSubscription(
        name: String,
        url: String,
        hidden: Boolean = false,
        serverTypes: String = "",
        reveal: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        val normalized = normalizeSubscriptionUrl(url)
        if (normalized.isBlank()) fail(R.string.home_err_paste_link)
        if (normalized.startsWith("http://", ignoreCase = true)) fail(R.string.home_err_https_only)
        val types = ServerMarkup.normalize(serverTypes)
        subDao.getAll().firstOrNull { it.url == normalized }?.let { existing ->
            val updated = existing.copy(
                name = name.trim().ifBlank { existing.name },
                nameLocked = existing.nameLocked || name.isNotBlank(),
                urlHidden = when {
                    hidden -> true
                    reveal -> false
                    else -> existing.urlHidden
                },
                serverTypes = types.ifBlank { existing.serverTypes },
                isEnabled = true
            )
            subDao.update(updated)
            try {
                refreshSubscription(existing.id)
            } catch (e: Exception) {
                subDao.update(existing)
                throw e
            }
            return@withContext existing.id
        }
        val id = subDao.insert(
            SubscriptionEntity(
                // Stored: the default name is written in the language it was added in.
                name = name.trim().ifBlank { context.getString(R.string.home_default_subscription_name) },
                nameLocked = name.trim().isNotBlank(),
                url = normalized,
                colorHex = SUBSCRIPTION_COLORS[(subDao.getAll().size) % SUBSCRIPTION_COLORS.size],
                urlHidden = hidden,
                serverTypes = types
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
        val fetched = try {
            fetchSubscriptionResilient(sub.url, sub.userAgent)
        } catch (e: Exception) {
            // A hidden link must not leak through an error text ("…host sub.example…").
            // Volta's own texts carry no address.
            if (sub.urlHidden && e !is UiTextException) throw IllegalStateException(concealAddress(e.message, sub.url))
            throw e
        }
        val body = fetched.body
        val parsed = ConfigParser.parseSubscriptionBody(body, subscriptionId = id)

        val serverName = parsed.title.trim()
        val displayName = if (sub.nameLocked) sub.name else serverName.ifBlank {
            parsed.servers.firstOrNull()?.remark?.takeIf { it.isNotBlank() }
        }.orEmpty().ifBlank { sub.name }

        val servers = ServerClassifier.mergeProviderUpdate(
            previous = serverDao.getBySubscription(id),
            incoming = parsed.servers,
            subscriptionId = id,
            serverTypes = sub.serverTypes
        )
        if (servers.isEmpty()) {
            fail(if (parsed.skipped.isNotEmpty()) R.string.home_err_only_unsupported else R.string.home_err_no_servers_in_subscription)
        }
        serverDao.replaceSubscription(id, servers)

        if (serverDao.getSelected() == null) {
            val fresh = serverDao.getBySubscription(id)
            (fresh.firstOrNull { it.role != ServerRole.AUTO } ?: fresh.firstOrNull())
                ?.let { serverDao.selectServer(it.id) }
        }

        // Routing shipped by the provider (routing header, happ://, Happ JSON).
        // A JSON subscription keeps its routing inside every config: that is used as-is
        // at connect time and is deliberately NOT turned into a separate profile.
        // Grouped JSON ({"wifi": […], "lte": […]}) may hold whole configs, yet its routing
        // is the top-level "routing" field, like any other subscription's.
        val jsonConfigs = looksLikeXrayConfigs(body) && parsed.servers.none { it.roleFromProvider }
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
        // Name and description are stored with the set, in the language it was made in.
        val profileName = routing.name.ifBlank { context.getString(R.string.home_routing_rules_name, displayName) }
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
            description = context.getString(R.string.home_routing_from_subscription, displayName),
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
            replacementFor(gone = null)?.let { serverDao.selectServer(it.id) }
        }
        reconcileTunnel()
    }

    suspend fun updateSubscription(id: Long, name: String, url: String) =
        withContext(Dispatchers.IO) {
            val current = subDao.getById(id) ?: fail(R.string.home_err_subscription_not_found)
            // A hidden link is never shown, so it can't be edited either: only the name.
            val normalized = if (current.urlHidden) current.url else normalizeSubscriptionUrl(url)
            if (normalized.isBlank()) fail(R.string.home_err_paste_link)
            if (normalized.startsWith("http://", ignoreCase = true)) fail(R.string.home_err_https_only)
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
            replacementFor(gone = null)?.let { serverDao.selectServer(it.id) }
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
        // Whatever the deletion already selected in its place, else the best one left.
        val replacement = serverDao.getSelected()?.takeIf { ConfigBuilder.canDial(it) }
            ?: replacementFor(gone = null)
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
            replacementFor(server)?.let { serverDao.selectServer(it.id) }
        }
        reconcileTunnel()
    }

    /**
     * Who takes over when the selected server disappears: one of the same kind, the
     * best measured first. A provider balancer only when nothing else is left — auto
     * modes never pick those, and whoever chose a regular node did not ask for one.
     */
    private suspend fun replacementFor(gone: ServerEntity?): ServerEntity? {
        val dialable = serverDao.getAll().filter { ConfigBuilder.canDial(it) }
        val regular = dialable.filter { it.role != ServerRole.AUTO }
        val sameKind = regular.filter { gone != null && it.role == gone.role }
        return sameKind.ifEmpty { regular }.ifEmpty { dialable }.minByOrNull { it.score }
    }

    suspend fun updateServerRole(id: Long, role: ServerRole) = serverDao.updateRole(id, role)

    suspend fun pingServer(id: Long): PingResult = withContext(Dispatchers.IO) {
        val server = serverDao.getById(id) ?: fail(R.string.home_err_server_not_found)
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
            val byId = all.associateBy { it.id }
            // Someone is watching the list, so this sweep is wider than the background
            // ones, gives up on a node sooner and writes each result as it lands.
            val results = engine.pingServers(
                servers = all,
                type = pingType,
                maxParallel = PingEngine.TCP_PARALLEL * 2,
                heavyParallel = PingEngine.HEAVY_PARALLEL + 4,
                realDelayTimeoutMs = PingEngine.SWEEP_TIMEOUT_MS
            ) { id, r -> byId[id]?.let { persist(it, r) } }
            results.count { it.second.success }
        }

    /**
     * Writes a measurement back: raw value, smoothed value and the failure streak. An
     * estimate (an OpenFlux node judged from outside) leaves the smoothed value to real
     * round trips.
     */
    private suspend fun persist(server: ServerEntity, result: PingResult) {
        val ok = result.success && result.latencyMs >= 0
        serverDao.updateHealth(
            id = server.id,
            latency = if (ok) result.latencyMs else -1,
            ewma = if (ok && !result.estimate) {
                AutoBalancer.blendEwma(server.ewmaMs, result.latencyMs)
            } else {
                server.ewmaMs
            },
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
            val profile = routingDao.getById(profileId) ?: fail(R.string.home_err_rules_not_found)
            if (profile.sourceSubscriptionId != null && profile.sourceSubscriptionId != subscriptionId) {
                fail(R.string.home_err_rules_of_other_subscription)
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

    suspend fun renameRoutingProfile(profileId: Long, name: String) {
        val profile = routingDao.getById(profileId) ?: return
        if (profile.isBuiltIn || name.isBlank()) return
        routingDao.update(profile.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
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
        val source = routingDao.getById(profileId) ?: fail(R.string.home_err_rules_not_found)
        routingDao.insert(
            source.copy(
                id = 0,
                name = context.getString(R.string.home_routing_copy_name, source.name),
                description = context.getString(R.string.home_routing_copy_description, source.name),
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

    private fun knownNetwork(maxAgeMs: Long = NETWORK_MEMO_TTL_MS): NetworkProbe.Outcome? {
        val memo = networkMemo ?: return null
        val fresh = System.currentTimeMillis() - memo.at < maxAgeMs
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
         * Reuse a site check of this same network no older than this instead of probing
         * again; 0 always probes afresh.
         */
        reuseNetworkMs: Long = 0,
        /**
         * Also move to the chosen node. Off for background runs, which measure first
         * and commit with [commitPick] only once they hold the connect lock.
         */
        select: Boolean = true
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
            knownNetwork = if (reuseNetworkMs > 0) knownNetwork(reuseNetworkMs) else null,
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
        if (select) commitPick(decision, exclude)
        decision
    }

    /** Moves to the node [decision] chose; a live tunnel follows by itself. */
    private suspend fun commitPick(decision: AutoBalancer.Decision, exclude: Long?) {
        // Leaving a failed node for one nobody measured would only trade one guess for
        // another; better to stay until something answers.
        val next = decision.selected?.takeIf { pick ->
            exclude == null || decision.ranked.any { it.ok && it.server.id == pick.id }
        } ?: return
        if (next.id != serverDao.getSelected()?.id) selectServer(next.id)
    }

    /**
     * While connected the lookup goes through the tunnel's own check port, which always
     * leaves through the exit node, so it shows the exit IP whatever the routing says.
     */
    suspend fun fetchIp(): IpInfo =
        IpChecker.fetch(socksPort = if (isConnected()) ConfigBuilder.EXIT_CHECK_PORT else null)

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
        if (!isCoreAvailable()) fail(R.string.home_err_build_cannot_connect)
        val all = serverDao.getAll()
        if (all.isEmpty()) fail(R.string.home_err_add_first)

        var server = serverDao.getSelected()
        if (server == null || server.id == excludeServerId) {
            val dialable = all.filter { it.id != excludeServerId && ConfigBuilder.canDial(it) }
            // A provider balancer only when nothing else is left.
            server = (AutoBalancer.poolFor(dialable, BalancerScope.ALL).ifEmpty { dialable })
                .minByOrNull { it.score }
                ?: fail(R.string.home_err_no_servers_available)
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
            preferServerRouting = routing.preferServerRouting,
            adBlock = prefs.adBlock.first(),
            blockTorrents = prefs.blockTorrents.first()
        )

        VoltaVpnService.ConnectionPlan(
            serverId = server.id,
            serverName = server.remark,
            configJson = config,
            mtu = prefs.mtu.first(),
            perAppMode = prefs.perAppMode.first(),
            perAppPackages = prefs.perAppPackages.first(),
            openFluxLink = if (server.protocol == ProtocolType.OPENFLUX) server.rawLink else "",
            helper = ConfigBuilder.helperSpec(server)
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
        when (effectiveMode()) {
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
        // The user goes first: a background comparison still measuring gives way
        // instead of making the button wait for it.
        refineJob?.cancel()
        backgroundPick?.cancel()
        _autoPicking.value = true
        try {
            connectMutex.withLock {
                pickJob = coroutineContext[Job]
                try {
                    connectWithModeLocked { phase ->
                        _pickPhase.value = phase
                        onPhase(phase)
                    }
                } finally {
                    pickJob = null
                }
            }
        } finally {
            _autoPicking.value = false
            _pickPhase.value = null
        }
    }

    @Volatile
    private var pickJob: Job? = null

    /**
     * Stops a connect that is still choosing a server, whoever started it: the button,
     * a tile or Always-on. A tunnel that is already up is left as it is.
     */
    fun cancelPick() {
        pickJob?.cancel()
        // The button lets go now; the measurements wind down on their own deadlines.
        _autoPicking.value = false
        _pickPhase.value = null
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
        // The simple screen can't show another mode, so a tile or shortcut that left one
        // behind is put back to Home+LTE.
        if (prefs.simpleMode.first() && prefs.connectMode.first() != ConnectMode.AUTO_HOME_LTE) {
            prefs.setConnectMode(ConnectMode.AUTO_HOME_LTE)
        }
        // Without LTE servers Home+LTE and LTE run as Home: no site probe at connect.
        val scope = effectiveMode().scope
        // Connect-time pick is the quick one: last good node or a short race, so the
        // tunnel is up in seconds. The thorough comparison follows in the background.
        val decision = scope?.let {
            runAutobalancer(it, onPhase, quick = true, reuseNetworkMs = CONNECT_MEMO_TTL_MS)
        }
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

    /** A background comparison still measuring; a connect cancels it. */
    @Volatile
    private var backgroundPick: Job? = null

    /**
     * After a quick connect, the full comparison (every node, finalists re-measured)
     * as soon as the tunnel is up and past its first health check. It runs outside the
     * tunnel and a clearly faster node then takes over in place.
     */
    private fun scheduleRefine() {
        refineJob?.cancel()
        refineJob = background.launch {
            withTimeoutOrNull(REFINE_WAIT_FOR_TUNNEL_MS) {
                vpnState.first { it == VpnConnectionState.CONNECTED }
            } ?: return@launch
            delay(REFINE_AFTER_CONNECT_MS)
            if (isConnected()) runCatching { reevaluateAuto() }
        }
    }

    /**
     * Measures in the background without holding up the button: the work runs on its
     * own and a connect may cancel it. Null when it was cancelled or failed.
     */
    private suspend fun <T> measureAside(block: suspend () -> T): T? {
        val work = background.async { block() }
        backgroundPick = work
        return try {
            work.await()
        } catch (e: Exception) {
            // Our own caller being cancelled is passed on (and takes the work along);
            // the work alone being cancelled or failing just means no answer.
            if (!currentCoroutineContext().isActive) {
                work.cancel()
                throw e
            }
            null
        } finally {
            if (backgroundPick === work) backgroundPick = null
        }
    }

    /** Re-run the auto choice on a live tunnel, e.g. after Wi-Fi ↔ mobile data. */
    suspend fun reevaluateAuto(): AutoBalancer.Decision? = withContext(Dispatchers.IO) {
        if (!isConnected() || _autoPicking.value) return@withContext null
        val scope = effectiveMode().scope ?: return@withContext null
        val liveId = VoltaVpnService.serverId.value
        // Measured without the connect lock, so the button never waits on it.
        val decision = measureAside { runAutobalancer(scope, select = false) }
            ?: return@withContext null
        connectMutex.withLock {
            // The button, a tile or a failover moved the tunnel meanwhile: theirs stands.
            if (!isConnected() || _autoPicking.value || VoltaVpnService.serverId.value != liveId) {
                return@withLock
            }
            commitPick(decision, exclude = null)
        }
        decision
    }

    // ------------------------------------------------------------ guard

    /** Optional switches (slow node, faster one found) — never more than 3 in 10 minutes. */
    private val switchLimiter = GuardPolicy.SwitchLimiter()

    @Volatile
    private var lastSlowRecheck = 0L

    /** Downloaded bytes at the last check, and on which node: data through it proves it alive. */
    @Volatile
    private var guardTrafficMark = 0L

    @Volatile
    private var guardTrafficServer = 0L

    private val exitProbeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(
                java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", ConfigBuilder.EXIT_CHECK_PORT)
                )
            )
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * A real site through the running tunnel's exit (its check port always leaves
     * through the node, whatever the routing). The time in ms, or -1 when nothing came
     * back: that is "data no longer passes through this server".
     */
    private suspend fun tunnelProbe(): Long = withContext(Dispatchers.IO) {
        for (url in TUNNEL_PROBE_URLS) {
            val start = System.nanoTime()
            val ok = runCatching {
                exitProbeClient.newCall(Request.Builder().url(url).head().build())
                    .execute().use { it.code in 100..599 }
            }.getOrDefault(false)
            if (ok) return@withContext ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        }
        -1L
    }

    /**
     * One background check on a live tunnel, every two minutes while the screen is on
     * (ConnectionGuard). Cheapest evidence first, and the group is measured only when
     * the evidence calls for it:
     *
     *  1. No network or a captive portal: nothing a server change could fix — stop.
     *  2. Home+LTE (only when LTE servers exist): the sites say which group fits this
     *     network; the wrong group moves over at once.
     *  3. The node in use is pinged directly (outside the tunnel). It answers:
     *     - slow for itself ([GuardPolicy.isSlow]) and a site through the tunnel agrees →
     *       the group is compared and a clearly faster node takes over;
     *     - otherwise the periodic full comparison, if one is due.
     *  4. It doesn't answer: it is only called dead when no data went through it since
     *     the last check, a site through the tunnel fails too, and the internet itself
     *     works. Then it is replaced.
     *
     * Everything runs outside the tunnel (this app is excluded from its own VPN), so a
     * dead server can't hide behind a working tunnel.
     */
    suspend fun guardCheck(full: Boolean) = withContext(Dispatchers.IO) {
        if (!isConnected() || _autoPicking.value) return@withContext
        val live = serverDao.getById(VoltaVpnService.serverId.value) ?: return@withContext
        if (com.ars3nb.volta.core.vpn.DeviceState.underlying(context) !=
            com.ars3nb.volta.core.vpn.DeviceState.Underlying.OK
        ) return@withContext
        // An OpenFlux carrier waits for the user to pass a check: the node isn't dead,
        // and moving off it would throw away the page the user is looking at.
        if (live.protocol == ProtocolType.OPENFLUX && OpenFluxRunner.waitsForCheck(live.rawLink)) {
            return@withContext
        }
        val mode = effectiveMode()
        val battery = prefs.batterySaver.first()
        val probe = NetworkProbe(battery)
        val now = System.currentTimeMillis()

        val down = traffic.value.downlinkBytes
        val downloaded = if (guardTrafficServer == live.id) (down - guardTrafficMark).coerceAtLeast(0) else 0L
        guardTrafficMark = down
        guardTrafficServer = live.id

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
            // An OpenFlux node's stored figure may be a guess from outside; only what
            // went through it counts as its usual.
            val usual = when {
                live.ewmaMs > 0 -> live.ewmaMs
                live.protocol == ProtocolType.OPENFLUX -> -1L
                else -> live.latencyMs
            }
            persist(live, answer)
            val slow = mode.scope != null &&
                GuardPolicy.isSlow(answer.latencyMs, usual, GuardPolicy.slowBarMs(live))
            if (slow && now - lastSlowRecheck >= GuardPolicy.SLOW_RECHECK_EVERY_MS) {
                // One slow ping is not a slow server: a site through the tunnel must agree.
                if (GuardPolicy.tunnelConfirmsSlow(tunnelProbe())) {
                    lastSlowRecheck = now
                    optionalRecheck(live.id, now)
                    return@withContext
                }
            }
            val power = com.ars3nb.volta.core.vpn.DeviceState.power(context)
            if (full && GuardPolicy.fullAllowed(power.batteryLow, power.charging)) {
                optionalRecheck(live.id, now)
            }
            return@withContext
        }

        // No answer from outside. Some networks drop the probe but not the traffic, so
        // before calling the server dead: did data go through it, does a site load?
        if (GuardPolicy.trafficProvesAlive(downloaded)) return@withContext
        if (tunnelProbe() >= 0) return@withContext
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

    /** A comparison that may switch to a faster node, within the flapping limit. */
    private suspend fun optionalRecheck(liveId: Long, now: Long) {
        if (!switchLimiter.allow(now)) return
        reevaluateAuto()
        if (serverDao.getSelected()?.id?.let { it != liveId } == true) switchLimiter.record(now)
    }

    /**
     * Wi-Fi ↔ mobile data under a live tunnel. Home+LTE probes the network again and
     * moves to the group that fits; Home or LTE alone checks the node in use right away
     * (its sockets died with the old network) instead of two minutes later.
     */
    suspend fun onNetworkChanged(): Unit = withContext(Dispatchers.IO) {
        forgetNetwork()
        if (!isConnected()) return@withContext
        when (effectiveMode()) {
            ConnectMode.MANUAL -> Unit // the service's own health check covers it
            ConnectMode.AUTO_HOME_LTE -> if (prefs.followNetwork.first()) reevaluateAuto() else guardCheck(full = false)
            else -> guardCheck(full = false)
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
        if (!isConnected() || VoltaVpnService.serverId.value != from.id) return
        if (failed) persist(from, PingResult(from.remark, PingType.REAL_DELAY, -1, false))
        val exclude = if (failed) from.id else null
        val decision = measureAside {
            runAutobalancer(
                scope,
                quick = true,
                instant = false,
                exclude = exclude,
                reuseNetworkMs = NETWORK_MEMO_TTL_MS,
                select = false
            )
        } ?: return
        connectMutex.withLock {
            // The button, a tile or the service's own failover got there first.
            if (!isConnected() || _autoPicking.value || VoltaVpnService.serverId.value != from.id) {
                return@withLock
            }
            commitPick(decision, exclude)
            if (decision.selected != null && serverDao.getSelected()?.id != from.id) scheduleRefine()
        }
    }

    suspend fun setConnectMode(mode: ConnectMode) = prefs.setConnectMode(mode)
    suspend fun setRefreshOnLaunch(v: Boolean) = prefs.setRefreshOnLaunch(v)
    suspend fun setFollowNetwork(v: Boolean) = prefs.setFollowNetwork(v)
    suspend fun setAutoRecheck(v: Boolean) = prefs.setAutoRecheck(v)
    suspend fun setHideBalancers(v: Boolean) = prefs.setHideBalancers(v)

    /** The simple screen has no mode picker, so it always runs in Home+LTE. */
    suspend fun setSimpleMode(v: Boolean) {
        if (v) prefs.setConnectMode(ConnectMode.AUTO_HOME_LTE)
        prefs.setSimpleMode(v)
    }


    fun disconnect() = VoltaVpnService.disconnect(context)

    // ------------------------------------------------------------- fetching

    private data class FetchedSubscription(val body: String, val routingHeader: String = "")

    /** An error text with every trace of a hidden subscription's address taken out. */
    private fun concealAddress(message: String?, url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        var text = message?.trim().orEmpty()
        listOf(url, host).filter { it.isNotBlank() }.forEach { text = text.replace(it, "…", ignoreCase = true) }
        // Anything still looking like an address goes too.
        text = text.replace(Regex("https?://\\S+"), "…")
        return text.ifBlank { context.getString(R.string.home_err_subscription_update_failed) }
    }

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
            fail(R.string.home_err_subscription_unreachable)
        } catch (e: SocketTimeoutException) {
            fail(R.string.home_err_subscription_timeout)
        } catch (e: SSLException) {
            fail(R.string.home_err_subscription_tls)
        } catch (e: IOException) {
            fail(R.string.home_err_subscription_download)
        }

        resp.use {
            if (!it.isSuccessful) fail(R.string.home_err_subscription_http, "${it.code}")
            val body = it.body?.string().orEmpty().removePrefix("\uFEFF")
            if (body.isBlank()) fail(R.string.home_err_subscription_empty)

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
    suspend fun setAdBlock(mode: AdBlockMode) = prefs.setAdBlock(mode)
    suspend fun setBlockTorrents(v: Boolean) = prefs.setBlockTorrents(v)
    suspend fun setMtu(v: Int) = prefs.setMtu(v)

    fun isConnected(): Boolean = vpnState.value == VpnConnectionState.CONNECTED

    companion object {
        /** Must match the port ConfigBuilder gives the http inbound. */
        const val LOCAL_HTTP_PORT = 10809

        /** A network check stays good this long on the same network. */
        private const val NETWORK_MEMO_TTL_MS = 10 * 60_000L

        /** A connect trusts a network check this recent instead of probing again. */
        private const val CONNECT_MEMO_TTL_MS = 3 * 60_000L

        /** After a quick connect: right past the first health check. */
        private const val REFINE_AFTER_CONNECT_MS = 4_000L

        /** A tunnel not up by then failed; the comparison is not needed. */
        private const val REFINE_WAIT_FOR_TUNNEL_MS = 30_000L

        private val TUNNEL_PROBE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )

        val SUBSCRIPTION_COLORS = listOf(
            "#C4784A", "#4A8FC4", "#5FB07A", "#B05FA8", "#C4A24A", "#4AC4B8"
        )
    }
}
