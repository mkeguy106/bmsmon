package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.NOTIFY_VANISH_GRACE_MS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.TempEnvelope
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.cToF
import dev.joely.bmsmon.model.decisionView
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.formatTemp
import dev.joely.bmsmon.model.nextTempNotify
import dev.joely.bmsmon.model.tempFillPct
import dev.joely.bmsmon.model.tempZone
import dev.joely.bmsmon.model.worstStageTemp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempAlertsTest {
    private val t = TempThresholds()                 // 5 / 45 / -12 / 53 (Redodo defaults)
    private val env = TempEnvelope()                 // cutoffs -20 / 60, locks 0 / 50

    private fun zone(c: Float) = tempZone(c, t, env)

    // --- cold ladder ---
    @Test fun coldCutoff() {
        assertEquals(TempRank.CUTOFF, zone(-20f).rank)
        assertEquals(TempRank.CUTOFF, zone(-25f).rank)
        assertEquals(TempSide.COLD, zone(-25f).side)
    }
    @Test fun coldCritical() {
        assertEquals(TempRank.CRITICAL, zone(-12f).rank)   // at the threshold
        assertEquals(TempRank.CRITICAL, zone(-15f).rank)
        assertEquals(TempRank.CUTOFF, zone(-20f).rank)     // cutoff wins below
    }
    @Test fun coldWarningIsChargeLock() {
        assertEquals(TempRank.WARNING, zone(0f).rank)      // 0°C charge lock
        assertEquals(TempRank.WARNING, zone(-5f).rank)
        assertEquals(TempSide.COLD, zone(0f).side)
    }
    @Test fun coldCaution() {
        assertEquals(TempRank.CAUTION, zone(5f).rank)      // at coldCaution
        assertEquals(TempRank.CAUTION, zone(3f).rank)
    }

    // --- safe ---
    @Test fun safeMidRange() {
        assertEquals(TempRank.SAFE, zone(25f).rank)
        assertEquals(TempSide.NONE, zone(25f).side)
    }

    // --- hot ladder (mirror) ---
    @Test fun hotCaution() {
        assertEquals(TempRank.CAUTION, zone(45f).rank)     // at hotCaution
        assertEquals(TempSide.HOT, zone(45f).side)
    }
    @Test fun hotWarningIsChargeLock() {
        assertEquals(TempRank.WARNING, zone(50f).rank)     // 50°C charge lock
    }
    @Test fun hotCritical() {
        assertEquals(TempRank.CRITICAL, zone(53f).rank)    // at hotCrit
        assertEquals(TempRank.CRITICAL, zone(58f).rank)
    }
    @Test fun hotCutoff() {
        assertEquals(TempRank.CUTOFF, zone(60f).rank)
        assertEquals(TempRank.CUTOFF, zone(65f).rank)
    }

    // --- gauge geometry: -30..+70 maps to 0..100% ---
    @Test fun fillPctMapping() {
        assertEquals(0f, tempFillPct(-30f), 0.001f)
        assertEquals(30f, tempFillPct(0f), 0.001f)
        assertEquals(100f, tempFillPct(70f), 0.001f)
        assertEquals(0f, tempFillPct(-50f), 0.001f)   // clamped
        assertEquals(100f, tempFillPct(90f), 0.001f)  // clamped
    }

    // --- unit formatting (default F; thresholds stored in C) ---
    @Test fun celsiusToFahrenheit() {
        assertEquals(32, cToF(0f))
        assertEquals(-4, cToF(-20f))
        assertEquals(140, cToF(60f))
        assertEquals(10, cToF(-12f))   // -12C -> 10F (the cold-crit reading)
    }
    @Test fun formatTempBothUnits() {
        assertEquals("10°F", formatTemp(-12f, TempUnit.F))
        assertEquals("-12°C", formatTemp(-12f, TempUnit.C))
        assertEquals("-4°F", formatTemp(-20f, TempUnit.F))
    }
    @Test fun formatDeltaConverts() {
        // margin from -12C to -20C cutoff is 8C -> 14F (round 8*9/5=14.4)
        assertEquals("14°F", formatDelta(8, TempUnit.F))
        assertEquals("8°C", formatDelta(8, TempUnit.C))
    }

    // --- headless temperature alarm: freshness gate + BLE-24 grace ---

    private val nowE = 1_000_000L

    /** A stage pack at [tempC]; [ageMs] = age of its last parsed frame (null = restored seed only). */
    private fun status(tempC: Float, ageMs: Long?) = BatteryStatus(
        Telemetry("x", soc = 50f, powerW = 0f, current = 0f, voltage = 13f, capacityAh = 50f, cellV = 3.3f, temp = tempC),
        reachable = true, lastFrameAtElapsedMs = ageMs?.let { nowE - it }, frameIntervalMs = STAGE_POLL_MS,
    )
    private val limits: (String) -> Pair<TempThresholds, TempEnvelope> = { t to env }

    // Pins the fix Tier 1 made without a test: the alarm reads the freshness decision view.
    @Test fun aSeedOrASilentStagePackNeverDrivesTheTemperatureAlarm() {
        val fleet = mapOf("A" to status(58f, ageMs = null), "B" to status(58f, ageMs = 61_000L))
        assertNull(worstStageTemp(decisionView(fleet, nowE), setOf("A", "B"), limits))
    }

    @Test fun theWorstLiveStagePackWins() {
        val fleet = mapOf("A" to status(46f, 1_000L), "B" to status(55f, 1_000L), "C" to status(61f, 1_000L))
        val w = worstStageTemp(decisionView(fleet, nowE), setOf("A", "B"), limits)!!   // C is off the stage
        assertEquals("B", w.addr)
        assertEquals(TempRank.CRITICAL, w.zone.rank)
    }

    @Test fun aFlappingStageKeepsTheTemperatureAlarmQuiet() {
        val g = NOTIFY_VANISH_GRACE_MS
        val first = nextTempNotify(null, null, "HOT:CRITICAL", present = true, holdable = true, nowMs = 0L, graceMs = g)
        assertTrue(first.post)
        val gone = nextTempNotify(first.key, first.vanishedAt, null, present = false, holdable = true, nowMs = 10_000L, graceMs = g)
        assertFalse(gone.post)
        assertFalse(gone.cancel)
        assertEquals("HOT:CRITICAL", gone.key)
        assertEquals(10_000L, gone.vanishedAt)
        val back = nextTempNotify(gone.key, gone.vanishedAt, "HOT:CRITICAL", present = true, holdable = true, nowMs = 70_000L, graceMs = g)
        assertFalse(back.post)
        assertFalse(back.cancel)
        assertNull(back.vanishedAt)
    }

    @Test fun theTemperatureGraceExpires() {
        val g = NOTIFY_VANISH_GRACE_MS
        val s = nextTempNotify("HOT:CRITICAL", 0L, null, present = false, holdable = true, nowMs = g, graceMs = g)
        assertTrue(s.cancel)
        assertNull(s.key)
    }

    @Test fun recoveryOrNoStageCancelsAtOnce() {
        val g = NOTIFY_VANISH_GRACE_MS
        assertTrue(nextTempNotify("HOT:CRITICAL", null, null, present = true, holdable = true, nowMs = 0L, graceMs = g).cancel)
        assertTrue(nextTempNotify("HOT:CRITICAL", null, null, present = false, holdable = false, nowMs = 0L, graceMs = g).cancel)
    }

    @Test fun anEscalationPostsAgain() {
        val s = nextTempNotify("HOT:CRITICAL", null, "HOT:CUTOFF", present = true, holdable = true, nowMs = 0L, graceMs = NOTIFY_VANISH_GRACE_MS)
        assertTrue(s.post)
        assertEquals("HOT:CUTOFF", s.key)
    }
}
