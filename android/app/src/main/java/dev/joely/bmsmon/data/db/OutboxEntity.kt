package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One pending upload: a fully-serialized ingest sample JSON object (no enclosing batch). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val enqueuedAt: Long,
)
