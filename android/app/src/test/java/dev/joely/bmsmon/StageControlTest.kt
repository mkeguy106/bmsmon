package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.Battery
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.DEFAULT_SEIZE_SOC
import dev.joely.bmsmon.model.Group
import dev.joely.bmsmon.model.PIN_HOLD_MS
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.SEIZE_SOC_OPTIONS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.StageConfig
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.engineDecision
import dev.joely.bmsmon.model.fallbackStage
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.hasDesiredLinks
import dev.joely.bmsmon.model.normalizeSeizeSoc
import dev.joely.bmsmon.model.pruneToRoster
import dev.joely.bmsmon.model.reconcileFleetNotifications
import dev.joely.bmsmon.model.resolveEngineStage
import dev.joely.bmsmon.model.seizeThresholdFor
import dev.joely.bmsmon.model.stageAddrsFor
import dev.joely.bmsmon.model.wantedAddrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's decision step (T1.2, 2026-10-02 review). BLE-14/UI-20: fleet-wide capacity
 * notifications were only re-evaluated when a STAGE pack reported, so a dark stage silenced every
 * low-battery notification; and stage resolution (incl. the seize) only ran in the ViewModel.
 * UI-29: only roster members may seize, and a memberless target falls back to the daily driver.
 */
class StageControlTest {

    private val nowMs = 1_000_000_000L
    private val nowE = 9_000_000L
    private val roster = DEFAULT_ROSTER
    private val ladder =
        AlertConfig(alertsOn = true, enabledThresholds = setOf(30, 25, 20, 15, 10, 5), criticalThreshold = 15)

    private fun tel(soc: Float, state: BatteryState = BatteryState.Idle) = Telemetry(
        "x", soc = soc, powerW = 0f,
        current = when (state) {
            BatteryState.Discharging -> -2f
            BatteryState.Charging -> 2f
            else -> 0f
        },
        voltage = 13f, capacityAh = soc, cellV = 3.3f, temp = 25f, state = state,
    )

    /** A pack heard from 1 s ago at the stage cadence. */
    private fun live(soc: Float, state: BatteryState = BatteryState.Idle) = BatteryStatus(
        tel(soc, state), reachable = true, lastFrameAtElapsedMs = nowE - 1_000L, frameIntervalMs = STAGE_POLL_MS,
    )

    private fun addrs(gid: String) = roster.groupById(gid)!!.targets.map { it.address }
    private fun base(gid: String, soc: Float, state: BatteryState = BatteryState.Idle) =
        addrs(gid).associateWith { live(soc, state) }

    private fun decide(
        fleet: Map<String, BatteryStatus>,
        cfg: StageConfig = StageConfig(dailyDriverId = "2012"),
        current: StageTarget = StageTarget.Base("2012"),
        r: Roster = roster,
        disabled: Set<String> = emptySet(),
        alerts: AlertConfig? = ladder,
        chargeAt: Map<String, Long> = emptyMap(),
    ) = engineDecision(r, fleet, nowE, nowMs, emptyMap(), cfg, current, disabled, alerts, chargeAt)

    // --- UI-19: the seize has its own level, separate from the notification ladder ---

    @Test fun seizeThresholdIsItsOwnLevelOnlyWhenBothTogglesAreOn() {
        assertEquals(30, seizeThresholdFor(alertsOn = true, seizeLowToStage = true, seizeSoc = 30))
        assertEquals(20, seizeThresholdFor(alertsOn = true, seizeLowToStage = true, seizeSoc = 20))
        assertEquals(15, seizeThresholdFor(alertsOn = true, seizeLowToStage = true, seizeSoc = 12))   // rounded UP to an offered level
        assertEquals(30, seizeThresholdFor(alertsOn = true, seizeLowToStage = true, seizeSoc = 60))   // never above the highest offered
        assertNull(seizeThresholdFor(alertsOn = false, seizeLowToStage = true, seizeSoc = 30))
        assertNull(seizeThresholdFor(alertsOn = true, seizeLowToStage = false, seizeSoc = 30))
    }

    @Test fun aStoredSeizeLevelNormalizesTowardMoreAlerting() {
        assertEquals(listOf(10, 15, 20, 25, 30), SEIZE_SOC_OPTIONS)
        assertEquals(30, DEFAULT_SEIZE_SOC)
        assertEquals(DEFAULT_SEIZE_SOC, normalizeSeizeSoc(null))                  // never touched → the old default
        SEIZE_SOC_OPTIONS.forEach { assertEquals(it, normalizeSeizeSoc(it)) }     // an offered level is kept
        // Between two levels → the HIGHER one (a pack is pulled to the stage sooner, never later).
        assertEquals(15, normalizeSeizeSoc(11))
        assertEquals(15, normalizeSeizeSoc(14))
        assertEquals(20, normalizeSeizeSoc(16))
        assertEquals(30, normalizeSeizeSoc(26))
        // Below the lowest → the lowest (still a higher threshold than stored).
        assertEquals(10, normalizeSeizeSoc(9))
        assertEquals(10, normalizeSeizeSoc(0))
        assertEquals(10, normalizeSeizeSoc(-5))
        assertEquals(10, normalizeSeizeSoc(Int.MIN_VALUE))
        // Above the highest → the highest offered (a seize is only offered at low levels).
        assertEquals(30, normalizeSeizeSoc(31))
        assertEquals(30, normalizeSeizeSoc(100))
        assertEquals(30, normalizeSeizeSoc(Int.MAX_VALUE))
    }

    // The seize is only the visual override: a low idle spare the seize must leave off the stage
    // (the chair is being driven) still gets its own fleet-wide notification.
    @Test fun aLowIdleSpareIsStillNotifiedWhileTheStageStaysOnTheDrivenChair() {
        val d = decide(
            base("2012", 70f, BatteryState.Discharging) + base("2024", 12f),
            cfg = StageConfig(dailyDriverId = "2012", seizeThreshold = 30),
        )
        assertEquals(StageTarget.Base("2012"), d.stage.target)
        assertEquals(addrs("2024").toSet(), reconcileFleetNotifications(d.capacity!!, emptyMap()).notify)
    }

    // Only alert-driving readings may seize: a STALE session reading can, a pack silent past the
    // freshness backstop (DISCONNECTED) cannot.
    @Test fun aStaleLowSpareSeizesButASilentOneDoesNot() {
        val cfg = StageConfig(dailyDriverId = "2012", seizeThreshold = 30)
        val stale = addrs("2024").associateWith { live(12f).copy(lastFrameAtElapsedMs = nowE - 15_000L) }
        assertEquals(StageTarget.Base("2024"), decide(stale, cfg = cfg).stage.target)
        val silent = addrs("2024").associateWith { live(12f).copy(lastFrameAtElapsedMs = nowE - 61_000L) }
        assertEquals(StageTarget.Base("2012"), decide(silent, cfg = cfg).stage.target)
    }

    // --- BLE-14 / UI-20 ---

    @Test fun lowNonStagePackIsEvaluatedWhileEveryStagePackIsUnreachable() {
        val dark = addrs("2012").associateWith { live(80f).copy(reachable = false) }
        val low = addrs("2016").first()
        val d = decide(dark + (low to live(12f)))   // seize OFF: notifications must not depend on it
        assertEquals(StageTarget.Base("2012"), d.stage.target)          // the stage stays dark…
        assertEquals(15, d.capacity!!.getValue(low).activeThreshold)    // …and the low spare still alerts
        assertEquals(setOf(low), reconcileFleetNotifications(d.capacity!!, emptyMap()).notify)
    }

    @Test fun userDisconnectedStageStillLetsOtherPacksAlert() {
        val stage = addrs("2012").toSet()
        val fleet = base("2012", 80f).mapValues { it.value.copy(reachable = false) } + base("2023", 14f)
        val d = decide(fleet, disabled = stage)
        assertTrue(d.stageAddrs.isEmpty())   // exactly the old dead-trigger condition (stageAddrs = ∅)
        assertEquals(setOf(15), d.capacity!!.values.mapNotNull { it.activeThreshold }.toSet())
    }

    // --- UI-16: the restored seed never alerts, seizes, or claims activity ---

    @Test fun reachableSeedNeverAlertsOrSeizes() {
        val seed = addrs("2016")
            .associateWith { BatteryStatus(tel(5f, BatteryState.Discharging), reachable = true) }
        val d = decide(seed, cfg = StageConfig(dailyDriverId = "2012", seizeThreshold = 30))
        assertTrue(d.capacity!!.isEmpty())
        assertEquals(StageTarget.Base("2012"), d.stage.target)   // not seized, and not "discharging"
    }

    @Test fun staleSessionReadingHoldsItsNotification() {
        val a = addrs("2016").first()
        val stale = live(12f).copy(lastFrameAtElapsedMs = nowE - 15_000L)   // past the 11.5 s stage window
        val plan = reconcileFleetNotifications(decide(mapOf(a to stale)).capacity!!, mapOf(a to 15))
        assertFalse(a in plan.cancel)
        assertFalse(a in plan.notify)
    }

    @Test fun packSilentPastTheBackstopCancelsItsNotification() {
        val a = addrs("2016").first()
        val silent = live(12f).copy(lastFrameAtElapsedMs = nowE - 61_000L)
        val plan = reconcileFleetNotifications(decide(mapOf(a to silent)).capacity!!, mapOf(a to 15))
        assertEquals(setOf(a), plan.cancel)
    }

    // A charging low spare neither seizes (UI-19) nor is notified.
    @Test fun chargingLowNonStagePackNeitherSeizesNorIsNotified() {
        val d = decide(
            base("2016", 12f, BatteryState.Charging),
            cfg = StageConfig(
                dailyDriverId = "2012", seizeThreshold = 30,
                manualStage = StageTarget.Base("2012"), manualPinnedAt = nowMs,
            ),
        )
        assertEquals(StageTarget.Base("2012"), d.stage.target)
        assertTrue(reconcileFleetNotifications(d.capacity!!, emptyMap()).notify.isEmpty())
    }

    @Test fun noAlertConfigYetMeansNoCapacityEvaluation() {
        assertNull(decide(base("2016", 5f), alerts = null).capacity)
    }

    // --- the per-pack charge latch the engine carries between decisions (UI-9) ---

    @Test fun aChargingFrameAdvancesTheChargeLatch() {
        val a = addrs("2016").first()
        val d = decide(mapOf(a to live(12f, BatteryState.Charging)), chargeAt = mapOf(a to 5L))
        assertEquals(nowMs, d.chargeAt.getValue(a))
        // An Idle flap inside the hold reads as charging, so the low pack is still not notified.
        val flap = decide(mapOf(a to live(12f)), chargeAt = d.chargeAt)
        assertEquals(nowMs, flap.chargeAt.getValue(a))
        assertTrue(flap.capacity!!.getValue(a).charging)
    }

    @Test fun withNoAlertConfigTheInputLatchIsCarriedUnchanged() {
        val a = addrs("2016").first()
        val latch = mapOf(a to 5L)
        val d = decide(mapOf(a to live(12f, BatteryState.Charging)), alerts = null, chargeAt = latch)
        assertEquals(latch, d.chargeAt)
    }

    // --- UI-29: roster membership and the empty-stage fallback ---

    @Test fun removedPackCannotSeizeOrAlert() {
        val ghost = "AA:BB:CC:DD:EE:FF"
        val d = decide(mapOf(ghost to live(5f)), cfg = StageConfig(dailyDriverId = "2012", seizeThreshold = 30))
        assertEquals(StageTarget.Base("2012"), d.stage.target)
        assertFalse(ghost in d.capacity!!)
    }

    @Test fun rosterPackStillSeizes() {
        val d = decide(base("2016", 20f), cfg = StageConfig(dailyDriverId = "2012", seizeThreshold = 30))
        assertEquals(StageTarget.Base("2016"), d.stage.target)
        assertEquals(addrs("2016").toSet(), d.stageAddrs)
    }

    @Test fun emptiedBaseFallsBackToTheDailyDriver() {
        val r = Roster(
            batteries = listOf(Battery("AA:00:00:00:00:01", "R-1", "One", "g2")),
            groups = listOf(Group("g1", "Emptied"), Group("g2", "Daily")),
        )
        val res = resolveEngineStage(
            r, emptyMap(), emptyMap(), StageConfig(dailyDriverId = "g2"), StageTarget.Base("g1"), nowMs,
        )
        assertEquals(StageTarget.Base("g2"), res.target)
    }

    @Test fun emptiedDailyDriverFallsBackToTheFirstPopulatedBase() {
        val r = Roster(
            batteries = listOf(Battery("AA:00:00:00:00:01", "R-1", "One", "g2")),
            groups = listOf(Group("g1", "Daily but empty"), Group("g2", "Populated")),
        )
        assertEquals(StageTarget.Base("g2"), fallbackStage(r, dailyDriverId = "g1"))
    }

    @Test fun ungroupedOnlyRosterFallsBackToASingle() {
        val r = Roster(batteries = listOf(Battery("AA:00:00:00:00:01", "R-1", "One", null)))
        assertEquals(StageTarget.Single("AA:00:00:00:00:01"), fallbackStage(r, dailyDriverId = "2012"))
    }

    @Test fun emptyRosterHasNoFallback() {
        assertNull(fallbackStage(Roster(), dailyDriverId = "2012"))
        val res = resolveEngineStage(Roster(), emptyMap(), emptyMap(), StageConfig(), StageTarget.Base("2012"), nowMs)
        assertEquals(StageTarget.Base("2012"), res.target)   // StageScreen's "Add a battery" owns this case
    }

    @Test fun removedSingleTargetFallsBack() {
        val res = resolveEngineStage(
            roster, emptyMap(), emptyMap(), StageConfig(dailyDriverId = "2023"),
            StageTarget.Single("AA:BB:CC:DD:EE:FF"), nowMs,
        )
        assertEquals(StageTarget.Base("2023"), res.target)
    }

    @Test fun pinnedOnlyWhileThePinHolds() {
        val pin = StageTarget.Base("2024")
        val held = resolveEngineStage(
            roster, emptyMap(), emptyMap(),
            StageConfig(dailyDriverId = "2012", manualStage = pin, manualPinnedAt = nowMs - 1_000L),
            StageTarget.Base("2012"), nowMs,
        )
        assertEquals(pin, held.target)
        assertTrue(held.pinned)
        // Expired, with the stage still sitting on the pinned base (current = pin): the target
        // matches the pin, so only the time clause can make it unpinned.
        val expired = resolveEngineStage(
            roster, emptyMap(), emptyMap(),
            StageConfig(dailyDriverId = "2012", manualStage = pin, manualPinnedAt = nowMs - PIN_HOLD_MS),
            pin, nowMs,
        )
        assertEquals(pin, expired.target)
        assertFalse(expired.pinned)
    }

    @Test fun stageAddrsDropDisabledAndNonMembers() {
        val (a, b) = addrs("2012")
        assertEquals(setOf(b), stageAddrsFor(StageTarget.Base("2012"), roster, setOf(a.lowercase())))
        assertTrue(stageAddrsFor(StageTarget.Single("AA:BB:CC:DD:EE:FF"), roster, emptySet()).isEmpty())
    }

    @Test fun pruneToRosterDropsRemovedPacks() {
        val keep = addrs("2012").first()
        val fleet = mapOf(keep to live(50f), "AA:BB:CC:DD:EE:FF" to live(50f))
        assertEquals(setOf(keep), pruneToRoster(fleet, roster).keys)
    }

    // --- BLE-27: does BLE have any link to want? ---

    @Test fun linksAreWantedWhileAnyRosterPackIsNotDisconnected() {
        val all = roster.allTargets().map { it.address }.toSet()
        assertTrue(hasDesiredLinks(roster, emptySet()))
        assertTrue(hasDesiredLinks(roster, all - all.first()))
        assertFalse(hasDesiredLinks(roster, all))                                   // "Disconnect all"
        assertFalse(hasDesiredLinks(roster, all.map { it.lowercase() }.toSet()))   // case-insensitive
        assertFalse(hasDesiredLinks(Roster(), emptySet()))                          // empty roster
        assertEquals(all - all.first(), wantedAddrs(roster, setOf(all.first().lowercase())))
    }

    // The engine threads its regen set into the notifier's evaluation.
    @Test fun aLowPackMidRegenKeepsItsNotification() {
        val a = addrs("2016").first()
        val d = engineDecision(
            roster, mapOf(a to live(12f, BatteryState.Charging)), nowE, nowMs, emptyMap(),
            StageConfig(dailyDriverId = "2012"), StageTarget.Base("2012"), emptySet(), ladder, emptyMap(),
            regenAddrs = setOf(a),
        )
        assertFalse(d.capacity!!.getValue(a).charging)
        assertTrue(reconcileFleetNotifications(d.capacity!!, mapOf(a to 15)).cancel.isEmpty())
    }
}
