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
    // The bare tsMs index (DATA-10) serves retention pruning + id-less time scans — the
    // (address, tsMs) composite can't (tsMs is its second column).
    indices = [Index(value = ["address", "tsMs"]), Index(value = ["sessionId"]), Index(value = ["tsMs"])],
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
    // GPS fix at sample time (nullable; null on link rows, pre-v4 rows, and GPS-off samples).
    // Stored locally so the range learner can compute Wh/mile offline — previously GPS only
    // rode the cloud outbox (see the 2026-07-11 discharge-estimate design).
    val lat: Double? = null,
    val lon: Double? = null,
    val gpsAccuracyM: Float? = null,
    val linkEvent: String?,
)
