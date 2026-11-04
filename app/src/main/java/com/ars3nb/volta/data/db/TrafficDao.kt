package com.ars3nb.volta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ars3nb.volta.data.model.TrafficDayEntity
import kotlinx.coroutines.flow.Flow

/** Per-day traffic through the tunnel; written by TrafficRecorder, read by the home card. */
@Dao
interface TrafficDao {
    @Query("SELECT * FROM traffic_days WHERE day >= :fromDay ORDER BY day ASC")
    fun observeSince(fromDay: Long): Flow<List<TrafficDayEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: TrafficDayEntity)

    @Query(
        "UPDATE traffic_days SET uplinkBytes = uplinkBytes + :up, " +
            "downlinkBytes = downlinkBytes + :down WHERE day = :day"
    )
    suspend fun addTo(day: Long, up: Long, down: Long)

    /**
     * Adds to a day's counters, creating the day on its first bytes. Two statements, not
     * an upsert: Android 8–10 ship an SQLite without `ON CONFLICT … DO UPDATE`.
     */
    @Transaction
    suspend fun add(day: Long, up: Long, down: Long) {
        insertIfMissing(TrafficDayEntity(day = day))
        addTo(day, up, down)
    }

    @Query("DELETE FROM traffic_days WHERE day < :day")
    suspend fun deleteBefore(day: Long)
}
