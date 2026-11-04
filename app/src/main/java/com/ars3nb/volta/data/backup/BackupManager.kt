package com.ars3nb.volta.data.backup

import android.content.Context
import com.ars3nb.volta.BuildConfig
import com.ars3nb.volta.R
import com.ars3nb.volta.core.backup.BackupCodec
import com.ars3nb.volta.core.backup.RestoreReport
import com.ars3nb.volta.data.db.VoltaDatabase
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.prefs.AppPreferences
import com.ars3nb.volta.data.repository.VoltaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saving everything to a file and putting it back on another phone (see [BackupCodec]
 * for what goes in). A restore only adds: a subscription or server already in the
 * app is left as it is, so opening the same file twice changes nothing.
 */
class BackupManager(private val context: Context, private val repo: VoltaRepository) {

    private val db = VoltaDatabase.get(context)
    private val prefs = AppPreferences(context)

    suspend fun export(): String = withContext(Dispatchers.IO) {
        val subscriptions = db.subscriptionDao().getAll()
        val appProfiles = db.routingDao().getAll().filter { it.sourceSubscriptionId == null }
        val nameById = appProfiles.associate { it.id to it.name }
        BackupCodec.encode(
            BackupCodec.Snapshot(
                subscriptions = subscriptions,
                subscriptionRouting = subscriptions
                    .mapNotNull { sub -> nameById[sub.routingProfileId]?.let { sub.id to it } }
                    .toMap(),
                servers = db.serverDao().getAll().filter { it.subscriptionId == null },
                routing = appProfiles.filter { BackupCodec.worthKeeping(it) },
                activeRouting = appProfiles.firstOrNull { it.isActive }?.name,
                settings = prefs.backupSettings(),
                appVersion = BuildConfig.VERSION_NAME,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun read(text: String): BackupCodec.Contents = BackupCodec.decode(text)

    suspend fun restore(contents: BackupCodec.Contents): RestoreReport = withContext(Dispatchers.IO) {
        repo.ensureSeeded()
        val routingRestored = restoreRouting(contents.routing, contents.activeRouting)
        val settingsRestored = prefs.restoreSettings(contents.settings)

        // Servers added by hand. One that is already there, under any name, is skipped.
        val serverDao = db.serverDao()
        val known = serverDao.getAll()
            .filter { it.subscriptionId == null }
            .map(BackupCodec::sameServerKey)
            .toMutableSet()
        var serversAdded = 0
        contents.servers.forEach { server ->
            if (known.add(BackupCodec.sameServerKey(server))) {
                serverDao.insert(server)
                serversAdded++
            }
        }

        // Subscriptions, matched by address. Each new one is loaded right away; one that
        // can't be loaded now stays, so the next refresh can bring its servers.
        val subDao = db.subscriptionDao()
        val urls = subDao.getAll().map { it.url }.toMutableSet()
        val profileIds = db.routingDao().getAll()
            .filter { it.sourceSubscriptionId == null }
            .associate { it.name to it.id }
        var added = 0
        var notLoaded = 0
        var alreadyThere = 0
        contents.subscriptions.forEach { restored ->
            if (!urls.add(restored.entity.url)) {
                alreadyThere++
                return@forEach
            }
            val id = subDao.insert(
                restored.entity.copy(
                    name = restored.entity.name.ifBlank { context.getString(R.string.home_default_subscription_name) },
                    routingProfileId = restored.routingName?.let { profileIds[it] } ?: 0L
                )
            )
            added++
            try {
                repo.refreshSubscription(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notLoaded++
            }
        }

        // Nothing selected yet (a new phone with only hand-added servers): take the first.
        if (serverDao.getSelected() == null) {
            serverDao.getAll().firstOrNull()?.let { serverDao.selectServer(it.id) }
        }

        RestoreReport(
            subscriptionsAdded = added,
            subscriptionsNotLoaded = notLoaded,
            subscriptionsAlreadyThere = alreadyThere,
            serversAdded = serversAdded,
            routingRestored = routingRestored,
            settingsRestored = settingsRestored,
            skipped = contents.skipped
        )
    }

    /**
     * Rule sets by name: one with the same name gets the saved rules, a new one is added.
     * A built-in that this version no longer has is skipped.
     */
    private suspend fun restoreRouting(profiles: List<RoutingProfileEntity>, activeName: String?): Int {
        val dao = db.routingDao()
        var count = 0
        profiles.forEach { saved ->
            val existing = dao.getAll().firstOrNull { it.sourceSubscriptionId == null && it.name == saved.name }
            when {
                existing != null -> {
                    dao.update(
                        existing.copy(
                            description = saved.description.ifBlank { existing.description },
                            useProviderRules = saved.useProviderRules,
                            rulesJson = saved.rulesJson,
                            domainStrategy = saved.domainStrategy,
                            remoteDns = saved.remoteDns,
                            domesticDns = saved.domesticDns,
                            dnsHostsJson = saved.dnsHostsJson,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    count++
                }

                !saved.isBuiltIn -> {
                    dao.insert(saved.copy(id = 0, isActive = false, updatedAt = System.currentTimeMillis()))
                    count++
                }
            }
        }
        activeName?.let { name ->
            dao.getAll().firstOrNull { it.sourceSubscriptionId == null && it.name == name }
                ?.let { dao.setActive(it.id) }
        }
        return count
    }
}
