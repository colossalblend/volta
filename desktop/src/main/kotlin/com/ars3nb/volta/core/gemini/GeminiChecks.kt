package com.ars3nb.volta.core.gemini

import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The Gemini verdict of every server that was checked, kept between launches, and the
 * checks themselves, as on Android (core/gemini/GeminiChecks.kt). Runs in the
 * controller's scope, so closing the menu a check was started from doesn't stop it.
 * With «after connecting» on, the server the tunnel comes up on is checked once it is
 * up, unless a recent verdict for it is already there.
 *
 * What Android reads from its VPN service and its preferences comes in here as
 * functions: [live] is the server the tunnel runs on and its check port (null while
 * not connected), [load] and [save] the stored verdicts.
 */
class GeminiChecks(
    private val scope: CoroutineScope,
    private val probe: GeminiProbe,
    /** The server by its id, from the store. */
    private val serverById: (Long) -> ServerEntity?,
    private val live: () -> Live?,
    private val load: () -> String,
    private val save: (String) -> Unit
) {
    /** The tunnel's server and the SOCKS port that always leaves through it. */
    data class Live(val serverId: Long, val checkPort: Int)

    private val _results = MutableStateFlow<Map<Long, GeminiResult>>(emptyMap())

    /** Last verdict per server id. */
    val results: StateFlow<Map<Long, GeminiResult>> = _results.asStateFlow()

    private val _running = MutableStateFlow<Set<Long>>(emptySet())

    /** Servers being checked right now. */
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    @Volatile
    private var started = false

    /**
     * Reads the stored verdicts and follows [connectedWithCheck]: the server the tunnel
     * is up on while «after connecting» is on, else 0.
     */
    fun start(connectedWithCheck: Flow<Long>) {
        if (started) return
        started = true
        scope.launch {
            _results.value = decode(runCatching { load() }.getOrDefault("")) + _results.value
            followConnections(connectedWithCheck)
        }
    }

    /** How [server] can be checked right now. */
    fun routeFor(server: ServerEntity, coreAvailable: Boolean): GeminiProbe.Route =
        GeminiProbe.routeFor(server, live()?.serverId, coreAvailable)

    /** Checks [serverId] now; a check of it already running is not started twice. */
    fun check(serverId: Long) {
        if (jobs[serverId]?.isActive == true) return
        jobs[serverId] = scope.launch { runCheck(serverId, attempts = 1) }
    }

    /** For the self-test's pictures: verdicts as if checked. */
    internal fun pretend(results: Map<Long, GeminiResult>) {
        _results.value = results
    }

    /** Verdicts of servers that are gone are dropped with them. */
    fun forget(keep: Set<Long>) {
        val before = _results.value
        val after = before.filterKeys { it in keep }
        if (after.size != before.size) {
            _results.value = after
            runCatching { save(encode(after)) }
        }
    }

    private suspend fun runCheck(serverId: Long, attempts: Int) {
        val server = serverById(serverId) ?: return
        _running.update { it + serverId }
        try {
            var result: GeminiResult? = null
            for (attempt in 1..attempts) {
                val now = live()
                val liveId = now?.serverId
                val through = GeminiProbe.routeFor(server, liveId, coreAvailable = true)
                val found = probe.check(server, liveId, now?.checkPort)
                // The check port leaves through whatever the tunnel runs on now: a switch
                // during the check would credit this server with another one's answer.
                if (through == GeminiProbe.Route.TUNNEL && live()?.serverId != serverId) return
                result = found
                if (!retryable(found) || attempt == attempts) break
                delay(RETRY_PAUSE_MS)
            }
            result?.let { remember(serverId, it) }
        } finally {
            _running.update { it - serverId }
        }
    }

    /**
     * Once the tunnel is up on a server (and after every switch), with the setting on:
     * a moment for the core to settle, then the check, unless a fresh verdict exists.
     */
    private suspend fun followConnections(connectedWithCheck: Flow<Long>) {
        connectedWithCheck
            .distinctUntilChanged()
            .collectLatest { id ->
                if (id <= 0) return@collectLatest
                val known = _results.value[id]
                if (known != null && known.status != GeminiStatus.BLOCKED_OR_ERROR &&
                    known.isFresh(System.currentTimeMillis(), AUTO_FRESH_MS)
                ) return@collectLatest
                delay(SETTLE_MS)
                if (jobs[id]?.isActive == true) return@collectLatest
                // Right after a connect the node may still drop the first request: one more try.
                val job = scope.launch { runCheck(id, attempts = 2) }
                jobs[id] = job
                job.join()
            }
    }

    private fun remember(serverId: Long, result: GeminiResult) {
        val all = _results.updateAndGet { it + (serverId to result) }
        runCatching { save(encode(all)) }
    }

    companion object {
        /** A verdict the after-connect check trusts instead of asking again. */
        const val AUTO_FRESH_MS = 6 * 60 * 60_000L

        /** Kept no longer than this, and no more than [KEEP] of them. */
        private const val KEEP_MS = 30L * 24 * 60 * 60_000L
        private const val KEEP = 300

        private const val SETTLE_MS = 2_500L
        private const val RETRY_PAUSE_MS = 3_000L

        private fun retryable(result: GeminiResult) =
            result.reason == GeminiReason.NETWORK || result.reason == GeminiReason.TIMEOUT

        internal fun encode(results: Map<Long, GeminiResult>, now: Long = System.currentTimeMillis()): String =
            JsonArray().apply {
                results.entries
                    .filter { now - it.value.checkedAt <= KEEP_MS }
                    .sortedByDescending { it.value.checkedAt }
                    .take(KEEP)
                    .forEach { (id, r) ->
                        add(JsonObject().apply {
                            addProperty("id", id)
                            addProperty("status", r.status.name)
                            addProperty("reason", r.reason.name)
                            r.region?.let { addProperty("region", it) }
                            addProperty("code", r.httpCode)
                            addProperty("at", r.checkedAt)
                        })
                    }
            }.toString()

        /** What [encode] wrote; anything unreadable is skipped, never an error. */
        internal fun decode(json: String): Map<Long, GeminiResult> {
            if (json.isBlank()) return emptyMap()
            val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyMap()
            return array.mapNotNull { element ->
                runCatching {
                    val o = element.asJsonObject
                    o.get("id").asLong to GeminiResult(
                        status = GeminiStatus.valueOf(o.get("status").asString),
                        reason = GeminiReason.valueOf(o.get("reason").asString),
                        region = o.get("region")?.takeIf { !it.isJsonNull }?.asString,
                        httpCode = o.get("code")?.asInt ?: 0,
                        checkedAt = o.get("at").asLong
                    )
                }.getOrNull()
            }
                // Verdicts made by the first rules (1.1.122), which read Gemini's other
                // feature strings and an off switch with no country as "doesn't work":
                // dropped, so the server is checked again with today's rules.
                .filterNot { (_, r) ->
                    r.status == GeminiStatus.UNSUPPORTED_REGION &&
                        (r.reason == GeminiReason.FLAG_OFF ||
                            (r.reason == GeminiReason.NOT_AVAILABLE_TEXT && r.region == null))
                }
                .toMap()
        }
    }
}
