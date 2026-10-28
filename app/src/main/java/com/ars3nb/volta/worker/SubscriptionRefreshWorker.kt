package com.ars3nb.volta.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ars3nb.volta.VoltaApp
import kotlinx.coroutines.flow.first

/**
 * Background subscription refresh with low impact: only enabled subscriptions that
 * are actually due by their own `profile-update-interval`, one at a time. Also the
 * occasional look for a newer build of the app.
 */
class SubscriptionRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VoltaApp ?: return Result.success()
        // Every few hours: a newer Volta on GitHub is announced with a notification.
        runCatching { app.updater.checkInBackground() }
        return try {
            val repo = app.repository
            val now = System.currentTimeMillis()
            val due = repo.subscriptions.first().filter { sub ->
                sub.isEnabled &&
                    sub.url.startsWith("https://", ignoreCase = true) &&
                    now - sub.lastUpdated >= sub.updateIntervalHours.coerceIn(1, 168) * 3_600_000L
            }
            if (due.isEmpty()) return Result.success()

            var failures = 0
            for (sub in due) {
                if (runCatching { repo.refreshSubscription(sub.id) }.isFailure) failures++
            }
            if (failures == 0) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
