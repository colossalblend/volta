package com.ars3nb.volta.core.gemini

import android.content.Context
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.prefs.AppPreferences
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The Gemini verdict of every server that was checked, kept between launches, and the
 * checks themselves. Runs in the app's scope, so closing the sheet a check was started
 * from doesn't stop it. With «after connecting» on, the server the tunnel comes up on is
 * checked once it is up, unless a recent verdict for it is already there.
 */
class GeminiChecks(
    context: Context,
    private val scope: CoroutineScope,
    /** The server by its id, from the database. */
    private val serverById: suspend (Long) -> ServerEntity?
) {
    private val prefs = AppPreferences(context)
    private val probe = GeminiProbe(context)

    private val _results = MutableStateFlow<Map<Long, GeminiResult>>(emptyMap())

    /** Last verdict per server id. */
    val results: StateFlow<Map<Long, GeminiResult>> = _results.asStateFlow()

    private val _running = MutableStateFlow<Set<Long>>(emptySet())

    /** Servers being checked right now. */
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    val afterConnect: Flow<Boolean> = prefs.geminiAfterConnect

    private val jobs = ConcurrentHashMap<Long, Job>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            _results.value = decode(prefs.geminiResults.first()) + _results.value
            followConnections()
        }
    }

    fun setAfterConnect(enabled: Boolean) {
        scope.launch { prefs.setGeminiAfterConnect(enabled) }
    }

    /** How [server] can be checked right now. */
    fun routeFor(server: ServerEntity): GeminiProbe.Route = GeminiProbe.routeFor(server, liveServerId())

    /** Checks [serverId] now; a check of it already running is not started twice. */
    fun check(serverId: Long) {
        if (jobs[serverId]?.isActive == true) return
        jobs[serverId] = scope.launch { runCheck(serverId, attempts = 1) }
    }

    private suspend fun runCheck(serverId: Long, attempts: Int) {
        val server = serverById(serverId) ?: return
        _running.update { it + serverId }
        try {
            var result: GeminiResult? = null
            for (attempt in 1..attempts) {
                val live = liveServerId()
                val through = GeminiProbe.routeFor(server, live)
                val found = probe.check(server, live)
                // The check port leaves through whatever the tunnel runs on now: a switch
                // during the check would credit this server with another one's answer.
                if (through == GeminiProbe.Route.TUNNEL && liveServerId() != serverId) return
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
    private suspend fun followConnections() {
        combine(VoltaVpnService.state, VoltaVpnService.serverId, prefs.geminiAfterConnect) { state, id, on ->
            if (on && state == VpnConnectionState.CONNECTED && id > 0) id else 0L
        }
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

    private fun liveServerId(): Long? =
        VoltaVpnService.serverId.value.takeIf {
            it > 0 && VoltaVpnService.state.value == VpnConnectionState.CONNECTED
        }

    private suspend fun remember(serverId: Long, result: GeminiResult) {
        val all = _results.updateAndGet { it + (serverId to result) }
        prefs.setGeminiResults(encode(all))
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
            }.toMap()
        }
    }
}
