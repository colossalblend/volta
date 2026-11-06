package com.ars3nb.volta.data.model

import androidx.annotation.StringRes
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ars3nb.volta.R
import com.ars3nb.volta.VoltaApp

/** Role of a server for the app-level autobalancer. */
enum class ServerRole {
    /** Regular foreign exit (used when foreign sites are reachable). */
    NORMAL,

    /** Whitelist-bypass / white-list circumvention node. */
    BYPASS_WHITELIST,

    /**
     * A provider-side balancer («Авто», «Автовыбор», «Балансер»…): the provider picks
     * the exit behind it. Never part of Home or LTE, and auto modes never pick it —
     * Volta does the balancing itself. Listed as «Балансеры», hidden by default.
     */
    AUTO
}

enum class ServerPool {
    ALL,
    NORMAL,
    BYPASS_WHITELIST,
    AUTO
}

fun ServerPool.matches(role: ServerRole): Boolean = when (this) {
    ServerPool.ALL -> true
    ServerPool.NORMAL -> role == ServerRole.NORMAL
    ServerPool.BYPASS_WHITELIST -> role == ServerRole.BYPASS_WHITELIST
    ServerPool.AUTO -> role == ServerRole.AUTO
}

/**
 * Everything Xray-core v26 can dial out with. HYSTERIA2 maps to the core's
 * `hysteria` outbound (version 2), WIREGUARD to the `wireguard` outbound.
 */
enum class ProtocolType {
    VLESS, VMESS, TROJAN, SHADOWSOCKS, SOCKS, HTTP, WIREGUARD, HYSTERIA2,

    /**
     * AmneziaWG (and WireGuard from a .conf), and Hysteria v1: run by the voltaproxy
     * helper, reached through its SOCKS5 port. [ServerEntity.rawLink] holds the .conf
     * text or the hysteria:// link the helper reads.
     */
    AMNEZIAWG, HYSTERIA,

    /** An openflux:// link: run by the OpenFlux helper, reached through its SOCKS5 port. */
    OPENFLUX,
    RAW_JSON, UNKNOWN;

    val label: String
        get() = when (this) {
            VLESS -> "VLESS"
            VMESS -> "VMess"
            TROJAN -> "Trojan"
            SHADOWSOCKS -> "Shadowsocks"
            SOCKS -> "SOCKS5"
            HTTP -> "HTTP"
            WIREGUARD -> "WireGuard"
            HYSTERIA2 -> "Hysteria2"
            AMNEZIAWG -> "AmneziaWG"
            HYSTERIA -> "Hysteria"
            OPENFLUX -> "OpenFlux"
            RAW_JSON -> "JSON"
            UNKNOWN -> "—"
        }
}

enum class PingType {
    /** Real end-to-end delay through the proxy, measured by the core. */
    REAL_DELAY,

    /** HTTP GET straight at the node endpoint — good for CDN-fronted hosts. */
    HTTP_GET,

    /** Plain TCP handshake to the node address. Cheapest, works offline-ish. */
    TCP,

    /** ICMP echo (often blocked by carriers). */
    ICMP
}

/** Which set of nodes an app-side autobalancer run considers. */
enum class BalancerScope {
    /** Probe the network first, then pick from the pool that suits the result. */
    ALL,

    /** Regular exits only — no site probing. */
    NORMAL,

    /** Whitelist-bypass exits only — no site probing. */
    BYPASS
}

/**
 * What the big connect button does. The three auto modes map onto the server groups:
 * Home = regular exits, LTE = whitelist-bypass exits, Home+LTE = probe the network
 * first and pick the group that can actually work on it.
 */
enum class ConnectMode {
    AUTO_HOME_LTE, AUTO_HOME, AUTO_LTE, MANUAL;

    val scope: BalancerScope?
        get() = when (this) {
            AUTO_HOME_LTE -> BalancerScope.ALL
            AUTO_HOME -> BalancerScope.NORMAL
            AUTO_LTE -> BalancerScope.BYPASS
            MANUAL -> null
        }

    /** The mode's name, for stringResource. */
    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            AUTO_HOME_LTE -> R.string.servers_mode_auto_home_lte
            AUTO_HOME -> R.string.servers_mode_auto_home
            AUTO_LTE -> R.string.servers_mode_auto_lte
            MANUAL -> R.string.servers_mode_manual
        }

    /**
     * The mode's name in the app's language, for places outside Compose (the tiles).
     * In unit tests, where there is no app, the enum name.
     */
    val title: String
        get() = runCatching { VoltaApp.instance.getString(titleRes) }.getOrElse { name }

    fun next(): ConnectMode = entries[(ordinal + 1) % entries.size]
}

enum class RouteOutbound {
    PROXY, DIRECT, BLOCK
}

/**
 * Ad blocking, in two layers (see ConfigBuilder.filterRules): AdGuard DNS answers every
 * lookup the core handles, so ads and trackers resolve to nothing, and the core's own
 * ad list blocks connections to them by name, for apps that find addresses elsewhere.
 */
enum class AdBlockMode(val dohUrl: String) {
    OFF(""),

    /** Ads, trackers and phishing. */
    STANDARD("https://dns.adguard-dns.com/dns-query"),

    /** The same plus adult sites (also blocked by name) and safe search (DNS only). */
    FAMILY("https://family.adguard-dns.com/dns-query"),

    /**
     * Standard's AdGuard DNS, plus Volta's strict list: cookie/consent banners,
     * personalisation and A/B testing (see AdBlockLists.STRICT). Stored by name, so it
     * comes last and the names before it never change.
     */
    STRICT("https://dns.adguard-dns.com/dns-query")
}

enum class VpnConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

/** How traffic is split between apps on the device. */
enum class PerAppMode {
    /** Every app goes through the tunnel. */
    OFF,

    /** Only the listed apps are tunnelled. */
    INCLUDE,

    /** The listed apps bypass the tunnel. */
    EXCLUDE
}

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameLocked: Boolean = false,
    val url: String,
    val lastUpdated: Long = 0,
    val userAgent: String = "",
    val updateIntervalHours: Int = 12,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    /** Raw subscription body cache */
    val rawBody: String = "",
    val expireAt: Long = 0,
    val trafficUsed: Long = 0,
    val trafficTotal: Long = 0,
    val supportUrl: String = "",
    val announce: String = "",
    val profileWebPageUrl: String = "",
    /** Provider-supplied routing profile JSON (if any). */
    val providerRoutingJson: String = "",
    val hwidEnabled: Boolean = true,
    val colorHex: String = "#C4784A",
    /**
     * Routing profile bound to THIS subscription. Isolation invariant: a profile
     * whose [RoutingProfileEntity.sourceSubscriptionId] is set may only ever be
     * referenced by that same subscription. 0 = "use the app default profile".
     */
    val routingProfileId: Long = 0,
    /**
     * The provider asked to keep the subscription link private (`visible_subs=false`, or
     * an encrypted `volta://crypt` link). The app then never shows, copies or edits it:
     * only the name can be changed, and errors never quote the address.
     */
    val urlHidden: Boolean = false,
    /**
     * Provider markup from the import link (`server-type=lte:1,2;home:3`): which servers,
     * by their 1-based position in the subscription, belong to which group. Kept with the
     * subscription so every refresh applies it again. See [com.ars3nb.volta.core.server.ServerMarkup].
     */
    val serverTypes: String = ""
)

@Entity(
    tableName = "servers",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("subscriptionId"), Index("remark")]
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long? = null,
    val remark: String,
    val protocol: ProtocolType = ProtocolType.VLESS,
    val address: String,
    val port: Int,
    /** UUID / password / auth secret, depending on protocol. */
    val uuid: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val network: String = "tcp",
    val security: String = "none",
    val sni: String = "",
    val fp: String = "",
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    val alpn: String = "",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val headerType: String = "",
    val rawLink: String = "",
    /** Full Xray outbound/config fragment when imported as JSON. */
    val rawJson: String = "",
    /**
     * Flat JSON map of every extra parameter the share link carried
     * (mode, seed, extra, obfs, upmbps, wg keys, allowInsecure, …).
     * Keeps new Xray knobs working without a schema migration each time.
     */
    val extraJson: String = "",
    val latencyMs: Long = -1,
    /** Smoothed latency used for ranking. -1 = never measured. */
    val ewmaMs: Long = -1,
    /** Consecutive failed measurements; reset on success. */
    val failStreak: Int = 0,
    val lastPingAt: Long = 0,
    val isFavorite: Boolean = false,
    val isSelected: Boolean = false,
    val role: ServerRole = ServerRole.NORMAL,
    val roleManual: Boolean = false,
    val sortOrder: Int = 0,
    val tags: String = "",
    /**
     * The provider said which group this node is in (JSON groups, a `[L]`/`[H]`/`[A]`
     * prefix or `server-type` in the import link). Such a role is never second-guessed
     * by the name check; only the user's own choice ([roleManual]) overrides it.
     */
    val roleFromProvider: Boolean = false
) {
    /** Ranking score: lower is better. Unmeasured and flaky nodes sink. */
    val score: Long
        get() {
            val base = when {
                ewmaMs >= 0 -> ewmaMs
                latencyMs >= 0 -> latencyMs
                else -> return Long.MAX_VALUE / 4
            }
            return base + failStreak.toLong() * 1_000L - if (isFavorite) 60L else 0L
        }

    val isHealthy: Boolean get() = failStreak == 0 && (ewmaMs >= 0 || latencyMs >= 0)
}

@Entity(tableName = "routing_profiles")
data class RoutingProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    /** Only meaningful for app-level profiles (sourceSubscriptionId == null). */
    val isActive: Boolean = false,
    val isBuiltIn: Boolean = false,
    /**
     * Non-null => this profile came from that subscription and is private to it.
     * It never appears as an app-level choice and can never be bound elsewhere.
     */
    val sourceSubscriptionId: Long? = null,
    /** When enabled, this profile may use provider rules stored with its subscription. */
    val useProviderRules: Boolean = false,
    /** Serialized list of RoutingRuleDto as JSON. */
    val rulesJson: String = "[]",
    val domainStrategy: String = "AsIs",
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val domesticDns: String = "https://dns.google/dns-query",
    val updatedAt: Long = System.currentTimeMillis(),
    /** Happ `Geoipurl`: the provider's own geoip.dat, downloaded per subscription. */
    val geoipUrl: String = "",
    /** Happ `Geositeurl`: the provider's own geosite.dat, downloaded per subscription. */
    val geositeUrl: String = "",
    /** Happ `LastUpdated`: a change forces the geodata to be downloaded again. */
    val geoVersion: String = "",
    /** When the geodata files were last downloaded successfully (0 = never). */
    val geoUpdatedAt: Long = 0,
    /** Happ `DnsHosts` as a flat JSON object {"domain": "ip"}. */
    val dnsHostsJson: String = ""
) {
    /** True for profiles the user may bind to any subscription. */
    val isShareable: Boolean get() = sourceSubscriptionId == null
}

data class RoutingRuleDto(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val outbound: RouteOutbound = RouteOutbound.PROXY,
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val ports: String = "",
    val sourcePorts: String = "",
    val network: String = "", // tcp/udp/empty
    val protocol: List<String> = emptyList(), // http,tls,bittorrent
    val inboundTags: List<String> = emptyList(),
    val users: List<String> = emptyList(),
    val attributes: String = "",
    val source: String = "user", // user | provider | builtin
    val sortOrder: Int = 0
)

data class PingResult(
    val target: String,
    val type: PingType,
    val latencyMs: Long,
    val success: Boolean,
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Not a round trip through the node but a judgement from outside: an OpenFlux
     * server that isn't running, whose carriers were reached (see PingEngine). It says
     * whether the node can work and roughly how fast; the node's smoothed figure is
     * left to real round trips.
     */
    val estimate: Boolean = false
)

data class SiteProbeResult(
    val host: String,
    val label: String,
    val category: SiteCategory,
    val latencyMs: Long,
    val reachable: Boolean
)

enum class SiteCategory {
    /** Home probes (google.com, ars3nb.ru, calculator888.ru): open internet. */
    FOREIGN,

    /** LTE probes (vk.ru, ya.ru, appbank.pw): what survives a mobile whitelist. */
    RU_WHITELIST
}

/** Network regime detected from startup probes. */
enum class NetworkRegime {
    /** Foreign sites reachable — use NORMAL servers. */
    OPEN,

    /** Only RU whitelist reachable — use BYPASS_WHITELIST servers. */
    WHITELIST_ONLY,

    /** Nothing reachable. */
    BLOCKED,

    /** Not measured yet. */
    UNKNOWN
}

data class IpInfo(
    val ip: String = "",
    /** Flag and name as found; the screen names [countryCode] in its own language instead. */
    val country: String = "",
    /** ISO code when the service gave one ("NL"), else "". */
    val countryCode: String = "",
    val city: String = "",
    val isp: String = "",
    val org: String = "",
    val timezone: String = "",
    val queriedAt: Long = 0
)

/** Live counters read from the running core. */
data class TrafficStats(
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val uplinkSpeed: Long = 0,
    val downlinkSpeed: Long = 0
)

data class SubscriptionImportResult(
    val servers: List<ServerEntity>,
    val routingJson: String = "",
    val title: String = "",
    val expireAt: Long = 0,
    val trafficUsed: Long = 0,
    val trafficTotal: Long = 0,
    val announce: String = "",
    val supportUrl: String = "",
    val webPageUrl: String = "",
    val updateIntervalHours: Int = 12,
    /** Links the parser recognised but the core cannot dial (tuic://, ssr://…). */
    val skipped: List<String> = emptyList()
)

/** One server's new measurement, written together with others (ServerDao.updateHealthAll). */
data class HealthUpdate(val id: Long, val latency: Long, val ewma: Long, val failStreak: Int)

/** Where a live tunnel is on its way to, as the screen names it. */
data class SwitchTarget(val serverId: Long, val name: String)
