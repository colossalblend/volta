package com.ars3nb.volta.core.balancer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Taps through five servers move the tunnel once, to the last one: each request waits
 * [quietMs] and is dropped when a newer one comes in meanwhile. A request already
 * running when a newer one arrives is cancelled too; the newer one does the work.
 */
class LastWins<T>(
    private val scope: CoroutineScope,
    private val quietMs: Long,
    private val action: suspend (T) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    private var latest = 0L

    /** The newest request's number; a request still current can tell by [isLatest]. */
    fun submit(value: T): Long = synchronized(lock) {
        job?.cancel()
        val ticket = ++latest
        job = scope.launch {
            delay(quietMs)
            if (isLatest(ticket)) action(value)
        }
        ticket
    }

    fun isLatest(ticket: Long): Boolean = synchronized(lock) { ticket == latest }

    /** Drops whatever waits. */
    fun cancel() = synchronized(lock) {
        job?.cancel()
        job = null
        latest++
    }
}
