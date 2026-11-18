package dev.joely.bmsmon.ui

import dev.joely.bmsmon.model.EstSource
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.rawCapacityAh
import dev.joely.bmsmon.model.rawSoc
import kotlin.math.roundToInt

// Text for the estimated SOC beside the BMS's own reading. Pure, so the three screens agree and the
// BMS value is ALWAYS rendered (a raw frame shows BMS = ours), never only on disagreement.

/** Our SOC: "92%", or "~58%" when it comes from the rested-voltage table. */
fun socText(t: Telemetry): String =
    (if (t.estSource == EstSource.VOLTAGE) "~" else "") + "${t.soc.roundToInt()}%"

/** The BMS's SOC under the pack name: "BMS 99%". */
fun bmsSocLine(t: Telemetry): String = "BMS ${t.rawSoc.roundToInt()}%"

/** The BMS's remaining capacity under the Capacity tile: "BMS 112 Ah". */
fun bmsCapLine(t: Telemetry): String = "BMS ${t.rawCapacityAh.roundToInt()} Ah"

/** The All Batteries capacity-row tail: "bms 99% · 112". */
fun bmsListTail(t: Telemetry): String = "bms ${t.rawSoc.roundToInt()}% · ${t.rawCapacityAh.roundToInt()}"
