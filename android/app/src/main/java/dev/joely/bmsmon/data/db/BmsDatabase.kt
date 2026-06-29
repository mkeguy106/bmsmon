package dev.joely.bmsmon.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SampleEntity::class, SessionEntity::class, RawFrameEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BmsDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun sessions(): SessionDao
    abstract fun rawFrames(): RawFrameDao

    companion object {
        fun create(context: Context): BmsDatabase =
            Room.databaseBuilder(context, BmsDatabase::class.java, "bms.db").build()
    }
}
