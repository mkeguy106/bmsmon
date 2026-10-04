package dev.joely.bmsmon

import dev.joely.bmsmon.model.AlertConfig
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.PackSoc
import dev.joely.bmsmon.model.StageItem
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.evalStageAlert
import dev.joely.bmsmon.ui.theme.SocSeverity
import dev.joely.bmsmon.ui.theme.socSeverityFor
import dev.joely.bmsmon.ui.theme.stageSeverityWord
import dev.joely.bmsmon.ui.theme.tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UI-21: SOC severity follows the user's ladder (the alerts' own rule), not fixed 15/30 bands. */
class SeverityTest {
    private val ladder = AlertConfig(alertsOn = true, enabledThresholds = setOf(30, 25, 20, 15, 10, 5), criticalThreshold = 15)

    @Test fun bandsFireAtTheRungLikeTheAlerts() {
        assertEquals(SocSeverity.NORMAL, socSeverityFor(31f, ladder))
        assertEquals(SocSeverity.WARNING, socSeverityFor(30f, ladder))
        assertEquals(SocSeverity.WARNING, socSeverityFor(16f, ladder))
        assertEquals(SocSeverity.CRITICAL, socSeverityFor(15f, ladder))
        assertEquals(SocSeverity.CRITICAL, socSeverityFor(4f, ladder))
    }

    @Test fun aRaisedCriticalLevelTurnsTheNumberRed() {
        // The finding: critical = 20, an 18 % pack showed amber under the hardcoded bands.
        assertEquals(SocSeverity.CRITICAL, socSeverityFor(18f, ladder.copy(criticalThreshold = 20, enabledThresholds = ladder.enabledThresholds + 20)))
    }

    @Test fun severityIsInformationNotAnAlarm() {
        assertEquals(SocSeverity.WARNING, socSeverityFor(25f, ladder.copy(alertsOn = false)))
        assertEquals(SocSeverity.NORMAL, socSeverityFor(5f, ladder.copy(enabledThresholds = emptySet())))
    }

    @Test fun everySeverityButNormalCarriesAWord() {
        assertNull(SocSeverity.NORMAL.tag())
        assertEquals("LOW", SocSeverity.WARNING.tag())
        assertEquals("CRIT", SocSeverity.CRITICAL.tag())
    }

    // The rungs a user switched off are skipped exactly as the alerts skip them — including the
    // critical level's own rung, which the ladder lets them turn off after picking it.
    @Test fun disabledRungsAreSkippedLikeTheAlertsSkipThem() {
        val holes = AlertConfig(alertsOn = true, enabledThresholds = setOf(30, 20, 10), criticalThreshold = 15)
        assertEquals(SocSeverity.NORMAL, socSeverityFor(31f, holes))
        assertEquals(SocSeverity.WARNING, socSeverityFor(30f, holes))
        assertEquals(SocSeverity.WARNING, socSeverityFor(25f, holes))
        assertEquals(SocSeverity.WARNING, socSeverityFor(15f, holes))   // the alert at 15 % is the 20 rung: a warning
        assertEquals(SocSeverity.WARNING, socSeverityFor(11f, holes))
        assertEquals(SocSeverity.CRITICAL, socSeverityFor(10f, holes))
        val onlyLow = AlertConfig(alertsOn = true, enabledThresholds = setOf(10), criticalThreshold = 15)
        assertEquals(SocSeverity.NORMAL, socSeverityFor(29f, onlyLow))
        assertEquals(SocSeverity.NORMAL, socSeverityFor(11f, onlyLow))
        assertEquals(SocSeverity.CRITICAL, socSeverityFor(10f, onlyLow))
    }

    /** Ladders a user can actually set from Settings › Alerts. */
    private val ladders = listOf(
        ladder,
        AlertConfig(true, setOf(30, 20, 10), 15),                // some rungs off, the critical one included
        AlertConfig(true, setOf(25, 15, 5), 15),                 // the top rung off
        AlertConfig(true, setOf(60, 30, 25, 20, 15, 10, 5), 20), // an early-warning rung + a raised critical level
        AlertConfig(true, ALERT_THRESHOLDS.toSet(), 5),          // every rung, the lowest critical level
        AlertConfig(true, setOf(95, 5), 30),                     // a critical level above every low rung but one
        AlertConfig(true, setOf(10), 15),                        // a single rung below the critical level
        AlertConfig(true, emptySet(), 15),                       // every rung off
    )

    // The pin: at every rung of the full ladder, on and either side of it, the number's severity is
    // exactly what the alert evaluation says — WARNING iff a rung has fired, CRITICAL iff the fired
    // rung is critical — whatever rungs the user enabled. The master switch only silences alerts.
    @Test fun severityAgreesWithTheAlertLadderAtEveryRungBoundary() {
        for (cfg in ladders) {
            for (rung in ALERT_THRESHOLDS + 100 + 0) {
                for (soc in listOf(rung - 1f, rung - 0.5f, rung.toFloat(), rung + 0.5f, rung + 1f)) {
                    val alert = evalStageAlert(listOf(PackSoc(soc, charging = false)), cfg)
                    val expected = when {
                        alert.activeThreshold == null -> SocSeverity.NORMAL
                        alert.critical -> SocSeverity.CRITICAL
                        else -> SocSeverity.WARNING
                    }
                    assertEquals("soc $soc on $cfg", expected, socSeverityFor(soc, cfg))
                    assertEquals("alerts off, soc $soc on $cfg", expected, socSeverityFor(soc, cfg.copy(alertsOn = false)))
                }
            }
        }
    }

    private fun pack(soc: Float, state: BatteryState = BatteryState.Discharging) = Telemetry(
        "x", soc = soc, powerW = 26f, current = -2f, voltage = 13f,
        capacityAh = 50f, cellV = 3.3f, temp = 25f, state = state,
    )

    // Fix round 1 (review M1): the stage word belongs to a LIVE reading that is not charging. A STALE
    // number is muted with its age and a DISCONNECTED pack has no number; while charging, the bolt and
    // the "to full" ETA say what is happening, the flash is suppressed, and the ETA needs the room.
    @Test fun theStageWordShowsOnlyOnALiveReadingThatIsNotCharging() {
        assertEquals("LOW", stageSeverityWord(StageItem(pack(25f), regen = false), ladder))
        assertEquals("CRIT", stageSeverityWord(StageItem(pack(12f, BatteryState.Idle), regen = false), ladder))
        assertNull(stageSeverityWord(StageItem(pack(80f), regen = false), ladder))   // NORMAL: no word
        assertNull("charging", stageSeverityWord(StageItem(pack(25f, BatteryState.Charging), regen = false), ladder))
        assertNull("STALE", stageSeverityWord(StageItem(pack(25f), regen = false, staleAgeMs = 15_000L), ladder))
        assertNull("DISCONNECTED", stageSeverityWord(StageItem(pack(25f), regen = false, connected = false), ladder))
    }

    // A Charging frame from regen braking is not charging (the ETA's own rule), so the word — and the
    // number above it — can't flicker on every regen burst mid-drive.
    @Test fun regenBrakingIsNotChargingForTheStageWord() {
        assertEquals("LOW", stageSeverityWord(StageItem(pack(25f, BatteryState.Charging), regen = true), ladder))
    }
}
