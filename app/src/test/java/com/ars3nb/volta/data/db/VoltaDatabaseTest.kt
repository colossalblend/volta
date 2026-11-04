package com.ars3nb.volta.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.data.model.TrafficDayEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The database opens on a phone that had the previous version. Room refuses to open a
 * migrated table that differs in any way from what it would create itself, so a slip
 * in the migration's SQL would crash every updated app on start.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class VoltaDatabaseTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun migrationsRunInOneUnbrokenChain() {
        val steps = VoltaDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until steps.size + 1).map { it to it + 1 }, steps)
    }

    @Test
    fun version7DatabaseOpensWithTrafficTable(): Unit = runBlocking {
        val name = "migration-7-8.db"
        context.deleteDatabase(name)
        // A database as version 7 left it: everything but the traffic table.
        Room.databaseBuilder(context, VoltaDatabase::class.java, name).build().apply {
            openHelper.writableDatabase.apply {
                execSQL("DROP TABLE traffic_days")
                version = 7
            }
            close()
        }

        val db = Room.databaseBuilder(context, VoltaDatabase::class.java, name)
            .addMigrations(*VoltaDatabase.MIGRATIONS)
            .build()
        try {
            val dao = db.trafficDao()
            dao.add(20_000, 10, 20)
            dao.add(20_000, 1, 2)
            dao.add(20_001, 0, 5)
            assertEquals(
                listOf(TrafficDayEntity(20_000, 11, 22), TrafficDayEntity(20_001, 0, 5)),
                dao.observeSince(0).first()
            )
            dao.deleteBefore(20_001)
            assertEquals(listOf(TrafficDayEntity(20_001, 0, 5)), dao.observeSince(0).first())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
