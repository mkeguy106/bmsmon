package dev.joely.bmsmon

import dev.joely.bmsmon.data.Persisted
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.Battery
import dev.joely.bmsmon.model.DEFAULT_DIM_LEVEL
import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.DEFAULT_STAGE_HOLD_MIN
import dev.joely.bmsmon.model.Group
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.StageTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.TempUnit
import dev.joely.bmsmon.model.stageAddrsFor
import dev.joely.bmsmon.monitor.MonitorState
import dev.joely.bmsmon.monitor.RestorePlan
import dev.joely.bmsmon.monitor.monitoringNotificationText
import dev.joely.bmsmon.monitor.restorePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the sticky-restart restore mapping (BLE-11) and the notification de-churn text. */
class MonitorRestoreTest {

    private fun persisted(
        monitoring: Boolean = true,
        roster: Roster? = null,
        lastStage: StageTarget? = null,
        dailyDriverId: String? = null,
        logging: Boolean = false,
        disabledAddrs: Set<String>? = null,
        lastTelemetry: Map<String, Telemetry> = emptyMap(),
        alertsOn: Boolean = true,
        enabledThresholds: Set<Int>? = null,
        criticalThreshold: Int? = null,
        tempFahrenheit: Boolean = true,
        tempAlertsEnabled: Boolean = true,
        cloudEnabled: Boolean = false,
        enrolled: Boolean = false,
        gpsEnabled: Boolean? = null,
        gpsPauseParked: Boolean = true,
        seizeLowToStage: Boolean = true,
        dynamicStage: Boolean? = null,
        stageHoldMinutes: Int? = null,
    ) = Persisted(
        accentArgb = null, powerArgb = null, manualMode = false, darkMode = false,
        dailyDriverId = dailyDriverId, lastStage = lastStage, dynamicStage = dynamicStage,
        stageHoldMinutes = stageHoldMinutes, monitoring = monitoring, logging = logging,
        alertsOn = alertsOn, enabledThresholds = enabledThresholds,
        criticalThreshold = criticalThreshold, seizeLowToStage = seizeLowToStage, keepScreenOn = true, sortKey = null,
        filters = null, filterBaseId = null, lastTelemetry = lastTelemetry,
        tempFahrenheit = tempFahrenheit, roster = roster, appearance = null,
        autoLuxThreshold = null, locked = false, csvImported = false,
        lockShowTime = true, lockShowWifi = true, lockShowBattery = true,
        lockLowRefresh = true, lockDimScreen = false, lockDimLevel = DEFAULT_DIM_LEVEL,
        gpsPauseParked = gpsPauseParked,
        disabledAddrs = disabledAddrs, cloudEnabled = cloudEnabled, apiBaseUrl = null,
        deviceId = null, enrolled = enrolled, gpsEnabled = gpsEnabled,
        importWatermark = 0L, importDone = false, tempThresholdsByProfile = emptyMap(),
        tempAlertsEnabled = tempAlertsEnabled, showTempGauge = true, tempGaugeSide = null,
        cloudSyncAlerts = true, pendingTempConfig = null,
    )

    private fun tel(soc: Float) = Telemetry(
        name = "R", soc = soc, powerW = 0f, current = 0f, voltage = 13.2f,
        capacityAh = 50f, cellV = 3.3f, temp = 20f,
    )

    private fun RestorePlan.addrs() = stageAddrsFor(stage, roster, disabled)

    // --- restorePlan ---

    @Test
    fun `monitoring off means nothing to restore`() {
        assertNull(restorePlan(persisted(monitoring = false)))
    }

    @Test
    fun `null roster falls back to default and stage restores from persisted base`() {
        val plan = restorePlan(persisted(lastStage = StageTarget.Base("2024")))!!
        assertEquals(DEFAULT_ROSTER, plan.roster)
        assertEquals(StageTarget.Base("2024"), plan.stage)
        assertEquals(setOf("C8:47:80:15:07:DE", "C8:47:80:15:25:01"), plan.addrs())
    }

    @Test
    fun `stale stage group falls back to daily driver base`() {
        val plan = restorePlan(
            persisted(lastStage = StageTarget.Base("gone"), dailyDriverId = "2016"),
        )!!
        assertEquals(StageTarget.Base("2016"), plan.stage)
        assertEquals(setOf("C8:47:80:15:DB:13", "C8:47:80:15:25:9A"), plan.addrs())
    }

    @Test
    fun `no persisted stage or daily driver uses first group`() {
        val plan = restorePlan(persisted())!!
        // DEFAULT_ROSTER's first group is 2012.
        assertEquals(setOf("C8:47:80:15:67:44", "C8:47:80:15:62:1B"), plan.addrs())
    }

    @Test
    fun `single stage restores when the address is still in the roster`() {
        val plan = restorePlan(persisted(lastStage = StageTarget.Single("C8:47:80:15:25:01")))!!
        assertEquals(setOf("C8:47:80:15:25:01"), plan.addrs())
    }

    @Test
    fun `stale single stage falls back to daily driver base`() {
        val plan = restorePlan(
            persisted(lastStage = StageTarget.Single("AA:BB:CC:DD:EE:FF"), dailyDriverId = "2023"),
        )!!
        assertEquals(setOf("C8:47:80:46:0A:D6", "C8:47:80:45:90:FB"), plan.addrs())
    }

    @Test
    fun `disabled packs are uppercased and removed from the stage`() {
        val plan = restorePlan(
            persisted(
                lastStage = StageTarget.Base("2012"),
                disabledAddrs = setOf("c8:47:80:15:67:44"),
            ),
        )!!
        assertEquals(setOf("C8:47:80:15:67:44"), plan.disabled)
        assertEquals(setOf("C8:47:80:15:62:1B"), plan.addrs())
    }

    @Test
    fun `custom roster is used as-is`() {
        val roster = Roster(
            batteries = listOf(Battery("AA:BB:CC:DD:EE:FF", "R-TEST", "Test", "g1")),
            groups = listOf(Group("g1", "Base 1")),
        )
        val plan = restorePlan(persisted(roster = roster, lastStage = StageTarget.Base("g1")))!!
        assertEquals(roster, plan.roster)
        assertEquals(setOf("AA:BB:CC:DD:EE:FF"), plan.addrs())
    }

    @Test
    fun `seed carries last telemetry marked unreachable`() {
        val plan = restorePlan(
            persisted(lastTelemetry = mapOf("C8:47:80:15:25:01" to tel(72f))),
        )!!
        val seeded = plan.seed["C8:47:80:15:25:01"]!!
        assertFalse(seeded.reachable)
        assertEquals(72f, seeded.telemetry!!.soc)
    }

    @Test
    fun `alert config restores with defaults when unset`() {
        val plan = restorePlan(persisted())!!
        assertTrue(plan.alertConfig.alertsOn)
        assertEquals(DEFAULT_THRESHOLDS.toSet(), plan.alertConfig.enabledThresholds)
        assertEquals(DEFAULT_CRITICAL_THRESHOLD, plan.alertConfig.criticalThreshold)
    }

    @Test
    fun `alert config restores persisted values`() {
        val plan = restorePlan(
            persisted(alertsOn = false, enabledThresholds = setOf(50, 20), criticalThreshold = 20),
        )!!
        assertFalse(plan.alertConfig.alertsOn)
        assertEquals(setOf(50, 20), plan.alertConfig.enabledThresholds)
        assertEquals(20, plan.alertConfig.criticalThreshold)
    }

    @Test
    fun `temp unit follows the fahrenheit preference`() {
        assertEquals(TempUnit.F, restorePlan(persisted(tempFahrenheit = true))!!.tempUnit)
        assertEquals(TempUnit.C, restorePlan(persisted(tempFahrenheit = false))!!.tempUnit)
    }

    @Test
    fun `gps active requires enrolled and cloud on`() {
        // gpsEnabled defaults to cloudEnabled (same reducer as the ViewModel).
        assertTrue(restorePlan(persisted(cloudEnabled = true, enrolled = true))!!.gpsActive)
        assertFalse(restorePlan(persisted(cloudEnabled = true, enrolled = false))!!.gpsActive)
        assertFalse(restorePlan(persisted(cloudEnabled = false, enrolled = true))!!.gpsActive)
        // Explicitly disabled overrides the cloud default.
        assertFalse(
            restorePlan(persisted(cloudEnabled = true, enrolled = true, gpsEnabled = false))!!.gpsActive,
        )
    }

    // A sticky restart is a fresh process: without carrying this the engine would fall back to its
    // field default (true) and keep pausing GNSS for a user who had turned that setting off.
    @Test
    fun `parked gps pause setting is restored`() {
        assertTrue(restorePlan(persisted(gpsPauseParked = true))!!.gpsPauseParked)
        assertFalse(restorePlan(persisted(gpsPauseParked = false))!!.gpsPauseParked)
    }

    @Test
    fun `logging flag is restored`() {
        assertTrue(restorePlan(persisted(logging = true))!!.logging)
        assertFalse(restorePlan(persisted(logging = false))!!.logging)
    }

    // --- T1.2: the headless engine resolves the stage from the same inputs the app pushes ---

    @Test
    fun `stage config restores the persisted stage settings`() {
        val plan = restorePlan(persisted(dynamicStage = false, stageHoldMinutes = 30, dailyDriverId = "2016"))!!
        assertEquals("2016", plan.stageConfig.dailyDriverId)
        assertFalse(plan.stageConfig.dynamicEnabled)
        assertEquals(30 * 60_000L, plan.stageConfig.holdMs)
        assertNull(plan.stageConfig.manualStage)   // pins never survive a restart, headless or not
    }

    @Test
    fun `stage config defaults match a fresh install`() {
        val c = restorePlan(persisted())!!.stageConfig
        assertTrue(c.dynamicEnabled)
        assertEquals(DEFAULT_STAGE_HOLD_MIN * 60_000L, c.holdMs)
        assertEquals("2012", c.dailyDriverId)
    }

    // Same daily-driver reducer as the ViewModel (`p.dailyDriverId ?: DEFAULT_GROUP_ID`), not the
    // old `?: ""` that silently fell through to the FIRST group. Only a roster whose first group
    // isn't DEFAULT_GROUP_ID tells the two apart (DEFAULT_ROSTER's first group is 2012).
    @Test
    fun `daily driver defaults to DEFAULT_GROUP_ID even when it is not the first group`() {
        val roster = Roster(
            batteries = listOf(
                Battery("AA:00:00:00:00:01", "R-1", "One", "2016"),
                Battery("AA:00:00:00:00:02", "R-2", "Two", DEFAULT_GROUP_ID),
            ),
            groups = listOf(Group("2016", "2016"), Group(DEFAULT_GROUP_ID, "2012")),
        )
        val plan = restorePlan(persisted(roster = roster))!!
        assertEquals(DEFAULT_GROUP_ID, plan.stageConfig.dailyDriverId)
        assertEquals(StageTarget.Base(DEFAULT_GROUP_ID), plan.stage)
    }

    @Test
    fun `headless seize threshold uses the same rule as the app`() {
        assertEquals(30, restorePlan(persisted())!!.stageConfig.seizeThreshold)   // default ladder top
        assertEquals(50, restorePlan(persisted(enabledThresholds = setOf(50, 20)))!!.stageConfig.seizeThreshold)
        assertNull(restorePlan(persisted(seizeLowToStage = false))!!.stageConfig.seizeThreshold)
        assertNull(restorePlan(persisted(alertsOn = false))!!.stageConfig.seizeThreshold)
    }

    // --- monitoringNotificationText (BLE-11 de-churn: text only changes when this changes) ---

    @Test
    fun `no reachable packs reads connecting`() {
        assertEquals("Connecting…", monitoringNotificationText(MonitorState()))
        // Unreachable / telemetry-less packs don't count.
        val st = MonitorState(
            fleet = mapOf(
                "A" to BatteryStatus(telemetry = tel(50f), reachable = false),
                "B" to BatteryStatus(telemetry = null, reachable = true),
            ),
        )
        assertEquals("Connecting…", monitoringNotificationText(st))
    }

    @Test
    fun `reachable packs read count and lowest soc`() {
        val st = MonitorState(
            fleet = mapOf(
                "A" to BatteryStatus(telemetry = tel(83.4f), reachable = true),
                "B" to BatteryStatus(telemetry = tel(51.6f), reachable = true),
                "C" to BatteryStatus(telemetry = tel(90f), reachable = false),
            ),
        )
        assertEquals("2 packs connected · lowest 52%", monitoringNotificationText(st))
    }

    @Test
    fun `single pack uses singular wording`() {
        val st = MonitorState(fleet = mapOf("A" to BatteryStatus(telemetry = tel(99.6f), reachable = true)))
        assertEquals("1 pack connected · lowest 100%", monitoringNotificationText(st))
    }

    @Test
    fun `identical fleets produce identical text so distinctUntilChanged suppresses the repost`() {
        val a = MonitorState(fleet = mapOf("A" to BatteryStatus(telemetry = tel(60.2f), reachable = true)))
        val b = MonitorState(fleet = mapOf("A" to BatteryStatus(telemetry = tel(60.4f), reachable = true)))
        // Different raw SOC, same rounded display → same string → no notification churn.
        assertEquals(monitoringNotificationText(a), monitoringNotificationText(b))
        assertNotNull(monitoringNotificationText(a))
    }
}
