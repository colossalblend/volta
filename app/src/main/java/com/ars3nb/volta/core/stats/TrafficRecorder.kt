package com.ars3nb.volta.core.stats

import android.content.Context
import android.util.Log
import com.ars3nb.volta.data.db.TrafficDao
import com.ars3nb.volta.data.db.VoltaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the per-day traffic. The tunnel hands it the bytes of every two-second tick;
 * they are written about twice a minute and once more when the tunnel goes down, so
 * the database is not touched on every tick. Its own scope: the last write must
 * outlive the service that asked for it.
 */
object TrafficRecorder {

    private const val TAG = "TrafficRecorder"
    private const val FLUSH_EVERY_MS = 30_000L

    /** Older days are dropped: the card never shows more than two months. */
    private const val KEEP_DAYS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = PendingTraffic()
    private val writes = Mutex()

    @Volatile
    private var dao: TrafficDao? = null

    @Volatile
    private var lastFlushAt = 0L

    @Volatile
    private var pruned = false

    fun init(context: Context) {
        if (dao == null) dao = VoltaDatabase.get(context).trafficDao()
    }

    /** Bytes through the VPN server since the previous call. */
    fun count(up: Long, down: Long, now: Long = System.currentTimeMillis()) {
        if (up <= 0L && down <= 0L) return
        pending.add(TrafficTally.dayOf(now), up, down)
        if (now - lastFlushAt >= FLUSH_EVERY_MS) flush(now)
    }

    /** Writes what is counted so far. Cheap to call when there is nothing. */
    fun flush(now: Long = System.currentTimeMillis()) {
        lastFlushAt = now
        val target = dao ?: return
        if (pending.isEmpty()) return
        scope.launch {
            writes.withLock {
                pending.drain().forEach { (day, bytes) ->
                    runCatching { target.add(day, bytes.up, bytes.down) }.onFailure { e ->
                        // Kept for the next write rather than lost.
                        Log.w(TAG, "could not save traffic", e)
                        pending.add(day, bytes.up, bytes.down)
                    }
                }
                if (!pruned) {
                    pruned = runCatching { target.deleteBefore(TrafficTally.dayOf(now) - KEEP_DAYS) }.isSuccess
                }
            }
        }
    }
}
