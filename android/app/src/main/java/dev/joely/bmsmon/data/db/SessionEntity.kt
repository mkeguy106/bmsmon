package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One monitoring session for one pack (a continuous run; a gap > SESSION_GAP_MS or a disconnect
 * starts a new one). Holds the rollups that power the aging graphs. Kept forever.
 */
@Entity(tableName = "sessions", indices = [Index(value = ["address", "startMs"])])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val startMs: Long,
    val endMs: Long,
    val sampleCount: Int,
    val peakPowerW: Float,
    val p95PowerW: Float,
    val meanPowerW: Float,
    val peakCurrentA: Float,
    val peakRegenW: Float,
    val energyWh: Float,
    val socStart: Float,
    val socEnd: Float,
    val minSoc: Float,
    val maxSoc: Float,
    val minVoltageUnderLoad: Float,
    val estInternalResistanceMohm: Float?,
    val irConfidence: Float,
    val sohEnd: Int,
    val fullChargeAhEnd: Float,
    val cyclesEnd: Int,
    val maxTempC: Float,
)
