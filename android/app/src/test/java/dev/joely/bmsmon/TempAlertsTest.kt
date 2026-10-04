package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.NOTIFY_VANISH_GRACE_MS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.TempAlarm
import dev.joely.bmsmon.model.TempEnvelope
import dev.joely.bmsmon.model.TempRank
import dev.joely.bmsmon.model.TempSide
import dev.joely.bmsmon.model.TempThresholds
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.cToF
import dev.joely.bmsmon.model.decisionView
import dev.joely.bmsmon.model.formatDelta
import dev.joely.bmsmon.model.formatTemp
import dev.joely.bmsmon.model.packTemps
import dev.joely.bmsmon.model.reconcileTempNotifications
import dev.joely.bmsmon.model.tempFillPct
import dev.joely.bmsmon.model.tempZone
import org.junit.Assert.assertEquals
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

    // --- headless temperature notifications: freshness gate + per-pack BLE-24 grace ---

    private val nowE = 1_000_000L

    /** A pack at [tempC]; [ageMs] = age of its last parsed frame (null = restored seed only). */
    private fun status(tempC: Float, ageMs: Long?) = BatteryStatus(
        Telemetry("x", soc = 50f, powerW = 0f, current = 0f, voltage = 13f, capacityAh = 50f, cellV = 3.3f, temp = tempC),
        reachable = true, lastFrameAtElapsedMs = ageMs?.let { nowE - it }, frameIntervalMs = STAGE_POLL_MS,
    )
    private val limits: (String) -> Pair<TempThresholds, TempEnvelope> = { t to env }

    // Pins the fix Tier 1 made without a test: the alarm reads the freshness decision view.
    @Test fun aSeedOrASilentPackNeverDrivesTheTemperatureAlarm() {
        val fleet = mapOf("A" to status(58f, ageMs = null), "B" to status(58f, ageMs = 61_000L))
        assertTrue(packTemps(decisionView(fleet, nowE), limits).isEmpty())
    }

    private val g = NOTIFY_VANISH_GRACE_MS
    private val hotCrit = TempAlarm(TempSide.HOT, TempRank.CRITICAL)
    private val stageAB = setOf("A", "B")
    private fun z(c: Float) = zone(c)

    @Test fun everyAlarmingStagePackPostsItsOwnAndOffStagePacksNever() {
        val zones = packTemps(
            decisionView(mapOf("A" to status(46f, 1_000L), "B" to status(55f, 1_000L), "C" to status(61f, 1_000L)), nowE),
            limits,
        ).mapValues { it.value.zone }
        val r = reconcileTempNotifications(zones, stageAB, last = emptyMap())
        assertEquals("C is off the stage; A is only CAUTION", setOf("B"), r.notify)
        assertEquals(mapOf("B" to hotCrit), r.newLast)
    }

    @Test fun aFlappingAlarmingPackAlarmsOnceAcrossReconnects() {
        var last: Map<String, TempAlarm> = emptyMap()
        var held: Map<String, Long> = emptyMap()
        var posts = 0
        var cancels = 0
        var t = 0L
        repeat(5) {
            val up = reconcileTempNotifications(mapOf("A" to z(55f)), stageAB, last, held, stageAB, t, g)
            posts += up.notify.size; cancels += up.cancel.size; last = up.newLast; held = up.heldSince
            t += 60_000L
            val down = reconcileTempNotifications(emptyMap(), stageAB, last, held, stageAB, t, g)
            posts += down.notify.size; cancels += down.cancel.size; last = down.newLast; held = down.heldSince
            t += 120_000L
        }
        assertEquals(1, posts)
        assertEquals(0, cancels)
    }

    // Final-wave MUST-FIX: the grace follows the ALARMING pack. The hot pack drops while its cooler
    // partner stays live — that used to read as "the stage recovered" and cancelled the alarm
    // (erring toward silence), then re-alarmed when the hot pack came back.
    @Test fun theHotPackDroppingWhileItsCoolerPartnerStaysLiveIsHeld() {
        val first = reconcileTempNotifications(mapOf("A" to z(55f), "B" to z(51f)), stageAB, emptyMap(), emptyMap(), stageAB, 0L, g)
        assertEquals(setOf("A"), first.notify)
        val dropped = reconcileTempNotifications(mapOf("B" to z(51f)), stageAB, first.newLast, first.heldSince, stageAB, 10_000L, g)
        assertTrue("held, not cancelled", dropped.cancel.isEmpty())
        assertTrue(dropped.notify.isEmpty())
        assertEquals(hotCrit, dropped.newLast["A"])
        assertEquals(10_000L, dropped.heldSince["A"])
        val back = reconcileTempNotifications(mapOf("A" to z(55f), "B" to z(51f)), stageAB, dropped.newLast, dropped.heldSince, stageAB, 70_000L, g)
        assertTrue("a reconnect at the same side/rank stays quiet", back.notify.isEmpty())
        assertTrue(back.cancel.isEmpty())
        assertTrue(back.heldSince.isEmpty())
    }

    @Test fun aDifferentPackReachingTheSameSideAndRankPostsItsOwn() {
        val r = reconcileTempNotifications(
            mapOf("B" to z(55f)), stageAB, last = mapOf("A" to hotCrit), heldSince = mapOf("A" to 0L),
            holdable = stageAB, nowMs = 10_000L, graceMs = g,
        )
        assertEquals(setOf("B"), r.notify)
        assertTrue("A is still held", r.cancel.isEmpty())
    }

    // The hold uses THAT pack's disconnected state, never the stage's: the user disconnecting the
    // alarming pack cancels its notification at once, even with its partner dark.
    @Test fun disconnectingTheAlarmingPackCancelsAtOnce() {
        // The engine drops a disconnected pack from the stage set and from the holdable set.
        val offStage = reconcileTempNotifications(emptyMap(), setOf("B"), mapOf("A" to hotCrit), holdable = setOf("B"), nowMs = 0L, graceMs = g)
        assertEquals(setOf("A"), offStage.cancel)
        val notHoldable = reconcileTempNotifications(emptyMap(), stageAB, mapOf("A" to hotCrit), holdable = setOf("B"), nowMs = 0L, graceMs = g)
        assertEquals(setOf("A"), notHoldable.cancel)
    }

    @Test fun theTemperatureGraceRunsFromTheDropThenCancels() {
        val held = reconcileTempNotifications(emptyMap(), stageAB, mapOf("A" to hotCrit), mapOf("A" to 0L), stageAB, g - 1, g)
        assertTrue(held.cancel.isEmpty())
        assertEquals("the clock is not restarted by later evaluations", 0L, held.heldSince["A"])
        val expired = reconcileTempNotifications(emptyMap(), stageAB, held.newLast, held.heldSince, stageAB, g, g)
        assertEquals(setOf("A"), expired.cancel)
        assertTrue(expired.newLast.isEmpty())
    }

    @Test fun aWorseReadingDuringAHoldAlarms() {
        val r = reconcileTempNotifications(mapOf("A" to z(61f)), stageAB, mapOf("A" to hotCrit), mapOf("A" to 0L), stageAB, 60_000L, g)
        assertEquals(setOf("A"), r.notify)
        assertEquals(TempAlarm(TempSide.HOT, TempRank.CUTOFF), r.newLast["A"])
        assertTrue(r.heldSince.isEmpty())
    }

    @Test fun aRecoveryDuringAHoldCancelsAtOnce() {
        val r = reconcileTempNotifications(mapOf("A" to z(50f)), stageAB, mapOf("A" to hotCrit), mapOf("A" to 0L), stageAB, 60_000L, g)
        assertEquals(setOf("A"), r.cancel)
        assertTrue(r.notify.isEmpty())
    }

    @Test fun nothingShownNothingCancelled() {
        val r = reconcileTempNotifications(mapOf("A" to z(25f)), stageAB, emptyMap(), emptyMap(), stageAB, 0L, g)
        assertTrue(r.cancel.isEmpty())
        assertTrue(r.notify.isEmpty())
        assertTrue(reconcileTempNotifications(emptyMap(), stageAB, emptyMap(), emptyMap(), stageAB, 0L, g).cancel.isEmpty())
    }

    // Ruling: a stage switch is not a flap. The alarm follows the new stage's packs; the departed
    // pack is held only while it is still read that hot, for the grace.
    @Test fun aStageSwitchIsNotAFlap() {
        val last = mapOf("A" to hotCrit)
        val newStage = setOf("C", "D")
        val stillHot = reconcileTempNotifications(mapOf("A" to z(55f)), newStage, last, emptyMap(), setOf("A", "C", "D"), 0L, g)
        assertTrue("still read hot: held", stillHot.cancel.isEmpty())
        val expired = reconcileTempNotifications(mapOf("A" to z(55f)), newStage, stillHot.newLast, stillHot.heldSince, setOf("A", "C", "D"), g, g)
        assertEquals("…for the grace only", setOf("A"), expired.cancel)
        val cooled = reconcileTempNotifications(mapOf("A" to z(50f)), newStage, last, emptyMap(), setOf("A", "C", "D"), 0L, g)
        assertEquals("read cooler: cancelled at once", setOf("A"), cooled.cancel)
        val dark = reconcileTempNotifications(emptyMap(), newStage, last, emptyMap(), setOf("A", "C", "D"), 0L, g)
        assertEquals("no reading off the stage: cancelled at once", setOf("A"), dark.cancel)
        val newStageHot = reconcileTempNotifications(mapOf("C" to z(-15f)), newStage, last, emptyMap(), setOf("A", "C", "D"), 0L, g)
        assertEquals("the new stage's alarming pack posts", setOf("C"), newStageHot.notify)
        assertEquals(setOf("A"), newStageHot.cancel)
    }

    @Test fun temperatureAlertsOffCancelsEverythingOnShow() {
        // The engine passes no readings and nothing holdable.
        val r = reconcileTempNotifications(emptyMap(), stageAB, mapOf("A" to hotCrit, "B" to hotCrit), emptyMap(), emptySet(), 0L, g)
        assertEquals(setOf("A", "B"), r.cancel)
    }
}
