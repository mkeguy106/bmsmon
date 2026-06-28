package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A raw BLE response frame (hex) kept for debugging. [reason]: periodic / realign / decode_fail. */
@Entity(tableName = "raw_frames", indices = [Index(value = ["tsMs"])])
data class RawFrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val tsMs: Long,
    val hex: String,
    val reason: String,
)
