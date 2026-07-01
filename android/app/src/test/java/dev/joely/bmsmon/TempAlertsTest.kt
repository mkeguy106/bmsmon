package dev.joely.bmsmon

import dev.joely.bmsmon.model.TempEnvelope
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.cToF
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.formatTemp
import dev.joely.bmsmon.model.tempFillPct
import dev.joely.bmsmon.model.tempZone
import org.junit.Assert.assertEquals
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
}
