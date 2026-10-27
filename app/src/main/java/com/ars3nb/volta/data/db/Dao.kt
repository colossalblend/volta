package com.ars3nb.volta.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SubscriptionEntity): Long

    @Update
    suspend fun update(entity: SubscriptionEntity)

    @Delete
    suspend fun delete(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE subscriptions SET routingProfileId = :profileId WHERE id = :id")
    suspend fun bindRoutingProfile(id: Long, profileId: Long)

    /** Used when a profile is deleted so no subscription points at a dead row. */
    @Query("UPDATE subscriptions SET routingProfileId = 0 WHERE routingProfileId = :profileId")
    suspend fun unbindRoutingProfile(profileId: Long)

    @Query("UPDATE subscriptions SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, remark ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder ASC, remark ASC")
    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder ASC, remark ASC")
    suspend fun getBySubscription(subId: Long): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE subscriptionId IS NULL ORDER BY sortOrder ASC, remark ASC")
    fun observeManual(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    fun observeSelected(): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelected(): ServerEntity?

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, remark ASC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int

    @Query("SELECT * FROM servers WHERE role = :role OR role = 'AUTO'")
    suspend fun getByRole(role: ServerRole): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ServerEntity>)

    /**
     * Swaps a subscription's server set atomically and repairs the selection:
     * merged rows keep their ids, so a still-present selected node survives; if the
     * selected node disappeared, the first node of the same subscription takes over
     * instead of leaving the app with nothing to connect to.
     */
    @Transaction
    suspend fun replaceSubscription(subId: Long, entities: List<ServerEntity>) {
        val previouslySelected = getSelected()
        deleteBySubscription(subId)
        insertAll(entities)
        if (previouslySelected?.subscriptionId == subId && getSelected() == null) {
            getBySubscription(subId).firstOrNull()?.let { selectServer(it.id) }
        }
    }

    @Update
    suspend fun update(entity: ServerEntity)

    @Query("UPDATE servers SET isSelected = 0")
    suspend fun clearSelection()

    @Transaction
    suspend fun selectServer(id: Long) {
        clearSelection()
        setSelected(id, true)
    }

    @Query("UPDATE servers SET isSelected = :selected WHERE id = :id")
    suspend fun setSelected(id: Long, selected: Boolean)

    @Query("UPDATE servers SET latencyMs = :latency, lastPingAt = :at WHERE id = :id")
    suspend fun updateLatency(id: Long, latency: Long, at: Long = System.currentTimeMillis())

    /** Single write for a measurement: raw latency, smoothed value and failure streak. */
    @Query(
        "UPDATE servers SET latencyMs = :latency, ewmaMs = :ewma, failStreak = :failStreak, " +
            "lastPingAt = :at WHERE id = :id"
    )
    suspend fun updateHealth(
        id: Long,
        latency: Long,
        ewma: Long,
        failStreak: Int,
        at: Long = System.currentTimeMillis()
    )

    @Query("UPDATE servers SET role = :role, roleManual = 1 WHERE id = :id")
    suspend fun updateRole(id: Long, role: ServerRole)

    @Query("UPDATE servers SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    @Delete
    suspend fun delete(entity: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface RoutingDao {
    @Query("SELECT * FROM routing_profiles ORDER BY isBuiltIn DESC, name ASC")
    fun observeAll(): Flow<List<RoutingProfileEntity>>

    /** App-level profiles only: the ones a user may bind to any subscription. */
    @Query(
        "SELECT * FROM routing_profiles WHERE sourceSubscriptionId IS NULL " +
            "ORDER BY isBuiltIn DESC, name ASC"
    )
    fun observeShareable(): Flow<List<RoutingProfileEntity>>

    @Query("SELECT * FROM routing_profiles ORDER BY isBuiltIn DESC, name ASC")
    suspend fun getAll(): List<RoutingProfileEntity>

    /** The app-level default, used by manual servers and unbound subscriptions. */
    @Query("SELECT * FROM routing_profiles WHERE isActive = 1 AND sourceSubscriptionId IS NULL LIMIT 1")
    fun observeActive(): Flow<RoutingProfileEntity?>

    @Query("SELECT * FROM routing_profiles WHERE isActive = 1 AND sourceSubscriptionId IS NULL LIMIT 1")
    suspend fun getActive(): RoutingProfileEntity?

    @Query("SELECT * FROM routing_profiles WHERE id = :id")
    suspend fun getById(id: Long): RoutingProfileEntity?

    @Query("SELECT * FROM routing_profiles WHERE sourceSubscriptionId = :subId LIMIT 1")
    suspend fun getBySubscription(subId: Long): RoutingProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoutingProfileEntity): Long

    @Update
    suspend fun update(entity: RoutingProfileEntity)

    @Query("UPDATE routing_profiles SET isActive = 0")
    suspend fun clearActive()

    @Transaction
    suspend fun setActive(id: Long) {
        val profile = getById(id) ?: return
        // Isolation invariant: a subscription-owned profile is never the app default.
        if (profile.sourceSubscriptionId != null) return
        clearActive()
        markActive(id)
    }

    @Query("UPDATE routing_profiles SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Delete
    suspend fun delete(entity: RoutingProfileEntity)

    @Query("DELETE FROM routing_profiles WHERE sourceSubscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    @Query("SELECT COUNT(*) FROM routing_profiles")
    suspend fun count(): Int
}
