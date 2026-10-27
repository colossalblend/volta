package com.ars3nb.volta.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity

class Converters {
    @TypeConverter
    fun fromProtocol(value: ProtocolType): String = value.name

    @TypeConverter
    fun toProtocol(value: String): ProtocolType =
        runCatching { ProtocolType.valueOf(value) }.getOrDefault(ProtocolType.UNKNOWN)

    @TypeConverter
    fun fromRole(value: ServerRole): String = value.name

    @TypeConverter
    fun toRole(value: String): ServerRole =
        runCatching { ServerRole.valueOf(value) }.getOrDefault(ServerRole.AUTO)
}

@Database(
    entities = [
        SubscriptionEntity::class,
        ServerEntity::class,
        RoutingProfileEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class VoltaDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun serverDao(): ServerDao
    abstract fun routingDao(): RoutingDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE routing_profiles ADD COLUMN useProviderRules INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN roleManual INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN nameLocked INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE subscriptions SET rawBody = ''")
                db.execSQL(
                    "UPDATE routing_profiles SET isActive = 0 WHERE sourceSubscriptionId IS NOT NULL"
                )
            }
        }

        /**
         * v5: protocol-agnostic extras, balancer health columns and the per-subscription
         * routing binding that keeps provider profiles isolated.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN extraJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE servers ADD COLUMN ewmaMs INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE servers ADD COLUMN failStreak INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN routingProfileId INTEGER NOT NULL DEFAULT 0"
                )
                // Bind every subscription that already owns a provider profile to it,
                // preserving the pre-migration behaviour without the global-active hack.
                db.execSQL(
                    """
                    UPDATE subscriptions SET routingProfileId = COALESCE((
                        SELECT p.id FROM routing_profiles p
                        WHERE p.sourceSubscriptionId = subscriptions.id LIMIT 1
                    ), 0)
                    """.trimIndent()
                )
                db.execSQL(
                    "UPDATE routing_profiles SET isActive = 0 WHERE sourceSubscriptionId IS NOT NULL"
                )
                db.execSQL("UPDATE servers SET ewmaMs = latencyMs WHERE latencyMs >= 0")
            }
        }

        /** v6: Happ routing geodata (Geoipurl/Geositeurl/LastUpdated) and DnsHosts. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_profiles ADD COLUMN geoipUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE routing_profiles ADD COLUMN geositeUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE routing_profiles ADD COLUMN geoVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE routing_profiles ADD COLUMN geoUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE routing_profiles ADD COLUMN dnsHostsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: VoltaDatabase? = null

        fun get(context: Context): VoltaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoltaDatabase::class.java,
                    "volta.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
