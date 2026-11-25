package dev.joely.bmsmon.model

/** Battery operating state, mirrors the BMS 0x13 state field (offset 88). */
enum class BatteryState { Idle, Charging, Discharging, Disabled }

/**
 * One battery pack's telemetry. The first block of fields maps 1:1 to the
 * Home dashboard stat grid; the rest are the extra values the 0x13 parse
 * provides for future use.
 */
data class Telemetry(
    val name: String,
    val soc: Float,          // %
    val powerW: Float,       // W  (voltage * |current|)
    val current: Float,      // A  (negative = discharge)
    val voltage: Float,      // V
    val capacityAh: Float,   // remaining Ah  -> "Capacity"
    val cellV: Float,        // representative (max) cell voltage
    val temp: Float,         // °C (cell temp)
    // production extras (not shown in the 6-stat grid yet)
    val soh: Int = 100,
    val cycles: Int = 0,
    val state: BatteryState = BatteryState.Idle,
    val fullChargeAh: Float = 0f,
    /** The BMS's pending correction to its own remaining-Ah counter. 0 normally; negative while it
     *  lowers the counter toward its rest-voltage estimate (about 2 h after the last current), so
     *  `capacityAh + pendingAh` is the value it is converging on. */
    val pendingAh: Float = 0f,
    val mosfetTemp: Int = 0,
    val cells: List<Float> = emptyList(),
    val protections: List<String> = emptyList(),
    /** The BMS's own SOC / remaining Ah, set only when [soc]/[capacityAh] carry an estimate
     *  (see [withEstimate]); null on raw frames. */
    val bmsSoc: Float? = null,
    val bmsCapacityAh: Float? = null,
    val estSource: EstSource? = null,
)
