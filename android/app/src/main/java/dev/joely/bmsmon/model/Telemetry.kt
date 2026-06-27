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
    val mosfetTemp: Int = 0,
    val cells: List<Float> = emptyList(),
    val protections: List<String> = emptyList(),
)

/** Initial demo packs (match the design prototype's seed values). */
val demoBatteries: List<Telemetry> = listOf(
    Telemetry(
        name = "Battery 1", soc = 46f, powerW = 20.6f, current = 0.33f,
        voltage = 24.8f, capacityAh = 85f, cellV = 3.71f, temp = 23.7f,
    ),
    Telemetry(
        name = "Battery 2", soc = 54f, powerW = 36.9f, current = 2.37f,
        voltage = 25.1f, capacityAh = 88f, cellV = 3.76f, temp = 22.6f,
    ),
)
