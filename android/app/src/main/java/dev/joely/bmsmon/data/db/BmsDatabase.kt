package dev.joely.bmsmon.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// exportSchema = true (DATA-10): the KSP-generated schema JSON lands in app/schemas/ (wired via
// room.schemaLocation in build.gradle.kts) and is checked into version control, so schema drift
// is reviewable and migration tests become possible.
@Database(
    entities = [SampleEntity::class, SessionEntity::class, RawFrameEntity::class, OutboxEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class BmsDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun sessions(): SessionDao
    abstract fun rawFrames(): RawFrameDao
    abstract fun outbox(): OutboxDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS outbox " +
                        "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, payload TEXT NOT NULL, enqueuedAt INTEGER NOT NULL)"
                )
            }
        }

        // DATA-10: bare index on samples.tsMs (retention pruning / time scans). The index name
        // must match what Room generates for @Index(value = ["tsMs"]) on the samples table.
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_samples_tsMs` ON `samples` (`tsMs`)")
            }
        }

        // Discharge-estimate feature: persist the GPS fix locally (it previously only rode the
        // cloud outbox) so Wh/mile can be learned on-phone, offline. Nullable — no backfill.
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN lon REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN gpsAccuracyM REAL")
            }
        }

        fun create(context: Context): BmsDatabase =
            Room.databaseBuilder(context, BmsDatabase::class.java, "bms.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
