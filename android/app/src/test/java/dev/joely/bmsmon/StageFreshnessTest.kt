package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.CHARGE_SUPPRESS_HOLD_MS
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.GroupActivity
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.groupById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-16 at the UiState seam: the stage and its overlay only act on THIS session's readings. */
class StageFreshnessTest {

    private val nowE = 7_000_000L
    private fun tel(soc: Float, state: BatteryState = BatteryState.Discharging) = Telemetry(
        "x", soc = soc, powerW = 26f, current = -2f, voltage = 13f,
        capacityAh = 50f, cellV = 3.3f, temp = 25f, state = state,
    )
    private val stage = DEFAULT_ROSTER.groupById("2012")!!.targets.map { it.address }

    private fun state(a: BatteryStatus, b: BatteryStatus) = UiState(
        monitoring = true, roster = DEFAULT_ROSTER, stageTarget = StageTarget.Base("2012"),
        fleet = mapOf(stage[0] to a, stage[1] to b), nowElapsedMs = nowE,
    )
    private fun live(soc: Float, state: BatteryState = BatteryState.Discharging) =
        BatteryStatus(tel(soc, state), reachable = true, lastFrameAtElapsedMs = nowE - 500L, frameIntervalMs = STAGE_POLL_MS)
    private fun seed(soc: Float) = BatteryStatus(tel(soc), reachable = true)
    private fun stale(soc: Float, ageMs: Long) =
        BatteryStatus(tel(soc), reachable = true, lastFrameAtElapsedMs = nowE - ageMs, frameIntervalMs = STAGE_POLL_MS)

    @Test fun reachableSeedRendersDisconnectedAndNeverFlashes() {
        val s = state(seed(12f), seed(12f))
        assertTrue(s.stageItems().none { it.connected })
        assertFalse(s.stageAlert().flashing)
        assertFalse(s.stageAlert().present)
    }

    @Test fun liveStagePacksRenderConnectedWithNoAge() {
        assertTrue(state(live(60f), live(61f)).stageItems().all { it.connected && it.staleAgeMs == null })
    }

    @Test fun staleSessionPackRendersMutedWithItsAge() {
        val items = state(stale(60f, 15_000L), live(61f)).stageItems()
        assertTrue(items[0].connected)
        assertEquals(15_000L, items[0].staleAgeMs)
        assertNull(items[1].staleAgeMs)
    }

    // One stage pack STALE-and-low, its partner LIVE-and-healthy: the low alert stays up.
    @Test fun staleLowPackKeepsTheAlertWhileItsPartnerIsLive() {
        val a = state(stale(12f, 15_000L), live(80f)).stageAlert()
        assertTrue(a.flashing)
        assertEquals(15, a.activeThreshold)
    }

    @Test fun packSilentPastTheBackstopStopsDrivingTheStage() {
        val s = state(stale(12f, 61_000L), live(80f))
        assertFalse(s.stageItems()[0].connected)
        assertNull(s.stageAlert().activeThreshold)
    }

    // --- the header (REGEN / DISCHARGING / CHARGING / IDLE) decides on the same view the engine does ---

    @Test fun seedOnlyStageReadsUnknownActivityNotDischarging() {
        // The restored seed is reachable=true with a Discharging reading; it must not light the header.
        assertEquals(GroupActivity.Unknown, state(seed(60f), seed(61f)).stageActivity)
    }

    @Test fun stagePacksSilentPastTheBackstopReadUnknownActivity() {
        assertEquals(GroupActivity.Unknown, state(stale(60f, 61_000L), stale(61f, 90_000L)).stageActivity)
    }

    @Test fun liveAndInSessionStalePacksStillDriveTheActivityHeader() {
        assertEquals(GroupActivity.Discharging, state(live(60f), live(61f)).stageActivity)
        // STALE with a known age is this session's reading: it holds the header, like it holds an alert.
        assertEquals(GroupActivity.Discharging, state(stale(60f, 15_000L), stale(61f, 15_000L)).stageActivity)
    }

    @Test fun stalePackInRegenAddrsDoesNotLightTheRegenHeader() {
        // Regen is a momentary event; the ring is already muted on a STALE pack, so the header agrees.
        val s = state(stale(60f, 15_000L), live(61f, BatteryState.Idle)).copy(regenAddrs = setOf(stage[0]))
        assertFalse(s.stageRegen)
    }

    @Test fun livePackInRegenAddrsLightsTheRegenHeader() {
        val s = state(live(60f), live(61f)).copy(regenAddrs = setOf(stage[0]))
        assertTrue(s.stageRegen)
        // A STALE partner doesn't cancel a LIVE regenerating pack.
        val mixed = state(live(60f), stale(61f, 15_000L)).copy(regenAddrs = setOf(stage[0]))
        assertTrue(mixed.stageRegen)
    }

    @Test fun seedPackInRegenAddrsDoesNotLightTheRegenHeader() {
        assertFalse(state(seed(60f), seed(61f)).copy(regenAddrs = setOf(stage[0])).stageRegen)
    }

    @Test fun monitoringOffEverythingIsDisconnected() {
        val s = state(live(12f).copy(reachable = false), live(80f).copy(reachable = false)).copy(monitoring = false)
        assertTrue(s.stageItems().none { it.connected })
    }

    // --- the ViewModel's freshness ticker step: it publishes only when something rendered changes
    //     (UI-26), and keeps the time-driven overlay latches moving with no engine emission ---

    @Test fun tickIsANoOpWhileEveryLabelHolds() {
        val s = state(live(60f), live(61f))
        assertSame(s, s.freshnessTick(nowMs = 1_000L, elapsedMs = nowE + 500L))
    }

    @Test fun tickAdvancesTheClockOnceAPackGoesStale() {
        val s = state(live(60f), live(61f))   // frames 500 ms old
        val later = nowE + 15_000L             // 15.5 s old: past 1.5 s cadence + 9 s grace
        val t = s.freshnessTick(nowMs = 1_000L, elapsedMs = later)
        assertEquals(later, t.nowElapsedMs)
        assertEquals(15_500L, t.stageItems()[0].staleAgeMs)
    }

    @Test fun chargeHoldExpiresOnTheTickWithoutAnEngineEmission() {
        // Latched by a Charging frame at t0, then the low stage pack sits on an Idle reading. When
        // nothing publishes from the engine, the hold must still expire so the real alert shows.
        val t0 = 1_000_000L
        val s = state(live(12f, BatteryState.Idle), live(80f, BatteryState.Idle))
            .copy(stageChargeHold = true, stageChargeLastAt = t0)
        assertFalse(s.stageAlert().flashing)
        assertSame(s, s.freshnessTick(nowMs = t0 + CHARGE_SUPPRESS_HOLD_MS - 1, elapsedMs = nowE))
        val t = s.freshnessTick(nowMs = t0 + CHARGE_SUPPRESS_HOLD_MS, elapsedMs = nowE)
        assertFalse(t.stageChargeHold)
        assertTrue(t.stageAlert().flashing)
    }

    @Test fun aChargingStageDoesNotRepublishEveryTick() {
        // While charging, the latch re-stamps its timestamp on every evaluation. That alone renders
        // nothing new, so the ticker must not publish it (a whole-tree recomposition every second).
        val s = state(live(12f, BatteryState.Charging), live(12f, BatteryState.Charging)).withChargeHold(1_000L)
        assertTrue(s.stageChargeHold)
        assertSame(s, s.freshnessTick(nowMs = 2_000L, elapsedMs = nowE))
    }

    @Test fun tickRearmsACapAckWhenTheLowPackGoesSilent() {
        // The low pack (acked at 15) is STALE but still holding the alert; its healthy partner is
        // alert-driving. 15 s later the low pack is past the backstop and drops out of the stage,
        // leaving only the healthy reading: nothing crosses 15 any more, so the ack re-arms - and
        // the tick must publish that, not drop it with a "nothing rendered" no-op.
        val s = state(stale(12f, 50_000L), live(80f)).copy(acknowledgedThresholds = setOf(15))
        assertEquals(setOf(15), s.freshnessTick(nowMs = 1_000L, elapsedMs = nowE + 1_000L).acknowledgedThresholds)
        val t = s.freshnessTick(nowMs = 1_000L, elapsedMs = nowE + 15_000L)
        assertTrue(t.acknowledgedThresholds.isEmpty())
    }

    @Test fun tickKeepsCapAcksWhenEveryStagePackIsSilent() {
        // Whole stage gone: there is no reading to judge a recovery by, so the ack is untouched.
        val s = state(stale(12f, 50_000L), stale(13f, 50_000L)).copy(acknowledgedThresholds = setOf(15))
        val t = s.freshnessTick(nowMs = 1_000L, elapsedMs = nowE + 15_000L)
        assertEquals(setOf(15), t.acknowledgedThresholds)
    }

    // The two ack clauses of the tick's "did anything rendered change" check, isolated: every
    // freshness label holds and the hold is unchanged, so only the pruned ack set can make the tick
    // publish. (A tap can leave an ack on a rung that stopped being crossed before the next prune.)

    @Test fun tickPublishesACapAckPruneWithNoLabelChange() {
        val s = state(live(80f), live(81f)).copy(acknowledgedThresholds = setOf(30))
        val t = s.freshnessTick(nowMs = 1_000L, elapsedMs = nowE)
        assertTrue(t.acknowledgedThresholds.isEmpty())
    }

    @Test fun tickPublishesATempAckPruneWithNoLabelChange() {
        val s = state(live(80f), live(81f)).copy(acknowledgedTempKeys = setOf("temp:HOT:CRITICAL"))   // packs read 25 C
        val t = s.freshnessTick(nowMs = 1_000L, elapsedMs = nowE)
        assertTrue(t.acknowledgedTempKeys.isEmpty())
    }
}
