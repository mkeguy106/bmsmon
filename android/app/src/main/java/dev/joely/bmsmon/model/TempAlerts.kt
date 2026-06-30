package dev.joely.bmsmon.model

import kotlin.math.roundToInt

/** User-tunable temperature thresholds (stored in °C). Defaults are the Redodo factory values. */
data class TempThresholds(
    val coldCautionC: Int = 5,
    val hotCautionC: Int = 45,
    val coldCritC: Int = -12,
    val hotCritC: Int = 53,
)

/**
 * Fixed hardware envelope from the battery profile: the BMS cutoffs and charge lock/resume points,
 * plus the factory default thresholds. Reset-to-defaults reads [defaults] from here.
 */
data class TempEnvelope(
    val coldCutoffC: Int = -20,
    val hotCutoffC: Int = 60,
    val chargeLockColdC: Int = 0,
    val chargeResumeColdC: Int = 5,
    val chargeLockHotC: Int = 50,
    val defaults: TempThresholds = TempThresholds(),
)

enum class TempSide { COLD, HOT, NONE }

/** Severity, ascending. Stage flashes on rank >= CRITICAL. */
enum class TempRank { SAFE, CAUTION, WARNING, CRITICAL, CUTOFF }

data class TempZone(val rank: TempRank, val side: TempSide)

/**
 * Classify a cell temperature against the thresholds + fixed envelope, severity-first (cold→hot).
 * WARNING is the charge-lock point (0°C cold / 50°C hot); CRITICAL fires before the BMS cutoff so the
 * chair is warned with power to spare.
 */
fun tempZone(tempC: Float, t: TempThresholds, env: TempEnvelope): TempZone = when {
    tempC <= env.coldCutoffC -> TempZone(TempRank.CUTOFF, TempSide.COLD)
    tempC <= t.coldCritC -> TempZone(TempRank.CRITICAL, TempSide.COLD)
    tempC <= env.chargeLockColdC -> TempZone(TempRank.WARNING, TempSide.COLD)
    tempC <= t.coldCautionC -> TempZone(TempRank.CAUTION, TempSide.COLD)
    tempC >= env.hotCutoffC -> TempZone(TempRank.CUTOFF, TempSide.HOT)
    tempC >= t.hotCritC -> TempZone(TempRank.CRITICAL, TempSide.HOT)
    tempC >= env.chargeLockHotC -> TempZone(TempRank.WARNING, TempSide.HOT)
    tempC >= t.hotCautionC -> TempZone(TempRank.CAUTION, TempSide.HOT)
    else -> TempZone(TempRank.SAFE, TempSide.NONE)
}

/** Mercury/marker height for the vertical gauge: the −30…+70°C span maps to 0…100%. */
fun tempFillPct(tempC: Float): Float = (tempC + 30f).coerceIn(0f, 100f)

/** °C margin to the relevant BMS cutoff for a [zone] (always >= 0 within the active side). */
fun tempMarginToCutoffC(tempC: Float, side: TempSide, env: TempEnvelope): Int = when (side) {
    TempSide.COLD -> (tempC.roundToInt() - env.coldCutoffC)
    TempSide.HOT -> (env.hotCutoffC - tempC.roundToInt())
    TempSide.NONE -> 0
}

/** Which kind of stage alert is showing (drives the overlay headline + worst-alert selection). */
enum class AlertKind { CAPACITY, TEMPERATURE }

/** Side of the SOC gauge the temperature gauge sits on. */
enum class GaugeSide { LEFT, RIGHT }

enum class TempUnit { C, F }

fun cToF(c: Float): Int = (c * 9f / 5f + 32f).roundToInt()

/** Render an absolute temperature in the user's unit (default °F). */
fun formatTemp(c: Float, unit: TempUnit): String =
    if (unit == TempUnit.F) "${cToF(c)}°F" else "${c.roundToInt()}°C"

/** Render a temperature *difference* (no +32 offset): Δ°F = round(Δ°C × 9/5). */
fun formatDelta(dC: Int, unit: TempUnit): String =
    if (unit == TempUnit.F) "${(dC * 9f / 5f).roundToInt()}°F" else "${dC}°C"
