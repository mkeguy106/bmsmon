package dev.joely.bmsmon

import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.Freshness
import dev.joely.bmsmon.model.LIVE_GRACE_MS
import dev.joely.bmsmon.model.SLOW_POLL_MS
import dev.joely.bmsmon.model.STAGE_POLL_MS
import dev.joely.bmsmon.model.STALE_MAX_MS
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.decisionView
import dev.joely.bmsmon.model.drivesAlerts
import dev.joely.bmsmon.model.formatAge
import dev.joely.bmsmon.model.freshness
import dev.joely.bmsmon.model.freshnessLabel
import dev.joely.bmsmon.model.freshnessLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-16 / UI-23 (2026-10-02 review): "is this reading live?" has exactly one definition on the
 * phone. Ages are monotonic; only a PARSED frame stamps one; a restored seed has none.
 */
class FreshnessTest {

    private val now = 5_000_000L
    private val tel = Telemetry(
        "x", soc = 40f, powerW = 26f, current = -2f, voltage = 13f,
        capacityAh = 40f, cellV = 3.3f, temp = 25f, state = BatteryState.Discharging,
    )

    private fun status(ageMs: Long?, reachable: Boolean = true, cadence: Long = STAGE_POLL_MS) =
        BatteryStatus(
            tel, reachable = reachable,
            lastFrameAtElapsedMs = ageMs?.let { now - it }, frameIntervalMs = cadence,
        )

    @Test fun freshFrameIsLive() {
        assertEquals(Freshness.Live, freshness(status(0L), now))
    }

    @Test fun liveWindowIsPollIntervalPlusGraceInclusive() {
        val edge = STAGE_POLL_MS + LIVE_GRACE_MS
        assertEquals(Freshness.Live, freshness(status(edge), now))
        assertEquals(Freshness.Stale(edge + 1), freshness(status(edge + 1), now))
    }

    @Test fun backgroundPacksGetTheSlowPollWindow() {
        val edge = SLOW_POLL_MS + LIVE_GRACE_MS
        assertEquals(Freshness.Live, freshness(status(edge, cadence = SLOW_POLL_MS), now))
        assertEquals(Freshness.Stale(edge + 1), freshness(status(edge + 1, cadence = SLOW_POLL_MS), now))
    }

    // M2: "two missed polls" must include the round-trip of the poll that finally answers. Frame at
    // t0 on the stage; poll 1 at 1.5 s times out at 5.5 s; after the 0.5 s breather poll 2 goes out
    // at 6.0 s and times out at 10.0 s; poll 3 goes out at 10.5 s and its frame lands ~10.6–11.0 s.
    @Test fun twoMissedStagePollsAndTheAnsweringRoundTripStayLive() {
        assertEquals(Freshness.Live, freshness(status(11_000L), now))
        assertEquals(10_000L, LIVE_GRACE_MS)
    }

    // A pack last polled at the slow cadence and then promoted to the stage (seize, or the other
    // base starts driving) is judged by the cadence it was LAST POLLED at, so it can't flash STALE
    // in the seconds before its first stage-cadence frame.
    @Test fun promotedPackKeepsTheWindowOfTheCadenceItWasLastPolledAt() {
        assertEquals(Freshness.Live, freshness(status(12_000L, cadence = SLOW_POLL_MS), now))
    }

    // An explicit pollIntervalMs overrides the cadence the pack was last polled at.
    @Test fun anExplicitPollIntervalOverridesTheStampedCadence() {
        val s = status(15_000L, cadence = SLOW_POLL_MS)                       // LIVE on its own cadence
        assertEquals(Freshness.Live, freshness(s, now))
        assertEquals(Freshness.Stale(15_000L), freshness(s, now, pollIntervalMs = STAGE_POLL_MS))
        val t = status(15_000L, cadence = STAGE_POLL_MS)                      // STALE on its own cadence
        assertEquals(Freshness.Live, freshness(t, now, pollIntervalMs = SLOW_POLL_MS))
    }

    // The UI clock can be read a moment before the engine stamps a frame.
    @Test fun frameStampedAfterTheUiClockReadsLive() {
        val s = BatteryStatus(tel, reachable = true, lastFrameAtElapsedMs = now + 40L, frameIntervalMs = STAGE_POLL_MS)
        assertEquals(Freshness.Live, freshness(s, now))
    }

    @Test fun restoredSeedIsNeverLive() {
        val seed = BatteryStatus(tel, reachable = true)   // reachable, but no frame this session
        assertEquals(Freshness.Stale(null), freshness(seed, now))
        assertFalse(freshness(seed, now).drivesAlerts())
    }

    @Test fun reachableWithoutAReadingIsConnecting() {
        assertEquals(Freshness.Stale(null), freshness(BatteryStatus(null, reachable = true), now))
    }

    @Test fun unreachablePackKeepsItsLastSeenAge() {
        assertEquals(Freshness.Disconnected(30_000L), freshness(status(30_000L, reachable = false), now))
        assertEquals(Freshness.Disconnected(null), freshness(BatteryStatus(tel, reachable = false), now))
        assertEquals(Freshness.Disconnected(null), freshness(null, now))
    }

    @Test fun silentReachablePackIsDisconnectedPastTheBackstop() {
        assertEquals(Freshness.Stale(STALE_MAX_MS), freshness(status(STALE_MAX_MS), now))
        assertEquals(Freshness.Disconnected(STALE_MAX_MS + 1), freshness(status(STALE_MAX_MS + 1), now))
    }

    @Test fun onlySessionReadingsDriveAlerts() {
        assertTrue(Freshness.Live.drivesAlerts())
        assertTrue(Freshness.Stale(15_000L).drivesAlerts())
        assertFalse(Freshness.Stale(null).drivesAlerts())
        assertFalse(Freshness.Disconnected(1_000L).drivesAlerts())
        assertFalse(Freshness.Disconnected(null).drivesAlerts())
    }

    @Test fun decisionViewHidesSeedsAndSilentPacksButKeepsSessionReadings() {
        val fleet = mapOf(
            "LIVE" to status(1_000L),
            "STALE" to status(20_000L),
            "SEED" to BatteryStatus(tel, reachable = true),
            "SILENT" to status(STALE_MAX_MS + 1),
            "GONE" to status(5_000L, reachable = false),
        )
        val view = decisionView(fleet, now)
        assertTrue(view.getValue("LIVE").reachable)
        assertTrue(view.getValue("STALE").reachable)
        assertFalse(view.getValue("SEED").reachable)
        assertFalse(view.getValue("SILENT").reachable)
        assertFalse(view.getValue("GONE").reachable)
        // The reading is never discarded — only its standing to drive decisions.
        assertEquals(tel, view.getValue("SEED").telemetry)
    }

    @Test fun ageFormatting() {
        assertEquals("0s", formatAge(999L))
        assertEquals("59s", formatAge(59_999L))
        assertEquals("1 min", formatAge(60_000L))
        assertEquals("59 min", formatAge(3_599_999L))
        assertEquals("1 h", formatAge(3_600_000L))
        assertEquals("2 d", formatAge(2 * 86_400_000L))
    }

    @Test fun aNegativeAgeFormatsAsZero() {
        // A frame stamped just after the UI clock was read must never render "Updated -1s ago".
        assertEquals("0s", formatAge(-1L))
        assertEquals("0s", formatAge(-90_000L))
    }

    @Test fun labels() {
        assertNull(freshnessLabel(Freshness.Live, monitoring = true))
        assertEquals("Updated 14s ago", freshnessLabel(Freshness.Stale(14_000L), monitoring = true))
        assertEquals("Connecting…", freshnessLabel(Freshness.Stale(null), monitoring = true))
        assertEquals("Out of range · seen 5s ago", freshnessLabel(Freshness.Disconnected(5_000L), monitoring = true))
        assertEquals("Out of range", freshnessLabel(Freshness.Disconnected(null), monitoring = true))
        assertEquals("Last seen 3 min ago", freshnessLabel(Freshness.Disconnected(180_000L), monitoring = false))
        assertEquals("Last known", freshnessLabel(Freshness.Disconnected(null), monitoring = false))
    }

    @Test fun labelMapOnlyChangesWhenARenderedLabelChanges() {
        val fleet = mapOf("A" to status(1_000L))
        assertEquals(freshnessLabels(fleet, now, true), freshnessLabels(fleet, now + 2_000L, true))     // still LIVE
        assertNotEquals(freshnessLabels(fleet, now, true), freshnessLabels(fleet, now + 15_000L, true)) // went STALE
    }
}
