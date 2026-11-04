package com.ars3nb.volta.data.backup

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.data.db.VoltaDatabase
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.prefs.AppPreferences
import com.ars3nb.volta.data.prefs.dataStore
import com.ars3nb.volta.data.repository.VoltaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The whole way: save on one "phone", wipe it, open the file on the empty one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BackupManagerTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun aSavedFileRebuildsAnEmptyPhone(): Unit = runBlocking {
        val db = VoltaDatabase.get(context)
        val repo = VoltaRepository(context)
        val prefs = AppPreferences(context)
        repo.ensureSeeded()

        val rules = db.routingDao().insert(RoutingProfileEntity(name = "Мои правила", rulesJson = "[]"))
        db.subscriptionDao().insert(
            SubscriptionEntity(name = "Мой VPN", nameLocked = true, url = UNREACHABLE + "a", routingProfileId = rules)
        )
        db.subscriptionDao().insert(SubscriptionEntity(name = "Скрытая", url = UNREACHABLE + "b", urlHidden = true))
        db.serverDao().insert(ServerEntity(remark = "Свой", address = "203.0.113.1", port = 443, uuid = "id-1"))
        prefs.setConnectMode(ConnectMode.AUTO_HOME)
        prefs.setPerAppMode(PerAppMode.INCLUDE)
        prefs.setPerAppPackages(setOf("org.telegram.messenger"))

        val manager = BackupManager(context, repo)
        val file = manager.export()
        assertTrue("a hidden link must not be readable in the file", !file.contains(UNREACHABLE + "b"))

        // The new phone: none of it is there.
        withContext(Dispatchers.IO) { db.clearAllTables() }
        context.dataStore.edit { it.clear() }

        val report = manager.restore(manager.read(file))
        assertEquals(2, report.subscriptionsAdded)
        // Their panels can't be reached from here: they stay, to be refreshed later.
        assertEquals(2, report.subscriptionsNotLoaded)
        assertEquals(1, report.serversAdded)
        assertEquals(1, report.routingRestored)

        val subs = db.subscriptionDao().getAll()
        assertEquals(listOf("Мой VPN", "Скрытая"), subs.map { it.name })
        assertEquals(UNREACHABLE + "b", subs[1].url)
        assertTrue(subs[1].urlHidden)
        val restoredRules = db.routingDao().getAll().first { it.name == "Мои правила" }
        assertEquals(restoredRules.id, subs[0].routingProfileId)
        val servers = db.serverDao().getAll()
        assertEquals(listOf("Свой"), servers.map { it.remark })
        assertTrue(servers.single().isSelected)
        assertEquals(ConnectMode.AUTO_HOME, prefs.connectMode.first())
        assertEquals(PerAppMode.INCLUDE, prefs.perAppMode.first())
        assertEquals(setOf("org.telegram.messenger"), prefs.perAppPackages.first())

        // Opening the same file again adds nothing.
        val again = manager.restore(manager.read(file))
        assertEquals(0, again.subscriptionsAdded)
        assertEquals(2, again.subscriptionsAlreadyThere)
        assertEquals(0, again.serversAdded)
        assertEquals(2, db.subscriptionDao().getAll().size)
        assertEquals(1, db.serverDao().getAll().size)
    }

    private companion object {
        /** Nothing listens there, so a refresh fails at once. */
        const val UNREACHABLE = "https://127.0.0.1:1/sub/"
    }
}
