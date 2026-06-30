package dev.joely.bmsmon.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SampleEntity::class, SessionEntity::class, RawFrameEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = false,
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

        fun create(context: Context): BmsDatabase =
            Room.databaseBuilder(context, BmsDatabase::class.java, "bms.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
