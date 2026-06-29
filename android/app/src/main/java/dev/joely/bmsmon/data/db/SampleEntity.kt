package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One poll's worth of telemetry for one pack (full resolution). Telemetry columns are null for
 * link-event rows ([linkEvent] = "Connected"/"Disconnected"). Pruned after the retention window.
 */
@Entity(
    tableName = "samples",
    indices = [Index(value = ["address", "tsMs"]), Index(value = ["sessionId"])],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val tsMs: Long,
    val sessionId: Long,
    val state: String?,
    val soc: Float?,
    val currentA: Float?,
    val powerW: Float?,
    val voltageV: Float?,
    val tempC: Float?,
    val mosfetTempC: Int?,
    val soh: Int?,
    val fullChargeAh: Float?,
    val remainingAh: Float?,
    val cycles: Int?,
    val cellMinV: Float?,
    val cellMaxV: Float?,
    val regen: Boolean,
    val linkEvent: String?,
)
