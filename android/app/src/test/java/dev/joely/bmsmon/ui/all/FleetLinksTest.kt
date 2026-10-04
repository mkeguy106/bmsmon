package dev.joely.bmsmon.ui.all

import dev.joely.bmsmon.model.DEFAULT_ROSTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FleetLinksTest {
    private val all = DEFAULT_ROSTER.batteries.map { it.address.uppercase() }.toSet()

    // Parked follow-up: the header used to decide from the FILTERED rows; with "Reachable" on, the
    // disconnected packs are filtered out, so "Reconnect all" could never appear.
    @Test fun theHeaderIsDecidedOnTheRosterNotTheFilteredList() {
        assertEquals(FleetHeaderActions(disconnectAll = true, reconnectAll = false), fleetHeaderActions(DEFAULT_ROSTER, emptySet()))
        assertEquals(FleetHeaderActions(disconnectAll = false, reconnectAll = true), fleetHeaderActions(DEFAULT_ROSTER, all))
        assertEquals(FleetHeaderActions(disconnectAll = true, reconnectAll = true), fleetHeaderActions(DEFAULT_ROSTER, setOf(all.first())))
    }

    @Test fun reconnectAllIsOfferedWithMonitoringOffButDisconnectAllIsNot() {
        val one = setOf(all.first())
        assertEquals(FleetHeaderActions(disconnectAll = false, reconnectAll = true), fleetHeaderActions(DEFAULT_ROSTER, one, monitoring = false))
        assertEquals(FleetHeaderActions(disconnectAll = false, reconnectAll = false), fleetHeaderActions(DEFAULT_ROSTER, emptySet(), monitoring = false))
    }

    @Test fun rowMembershipIsCaseInsensitive() {
        assertEquals(true, isUserDisconnected("c8:47:80:15:25:01", setOf("C8:47:80:15:25:01")))
        assertEquals(false, isUserDisconnected("C8:47:80:15:25:02", setOf("C8:47:80:15:25:01")))
    }

    @Test fun addressesCompareCaseInsensitively() {
        assertEquals(1, userDisconnectedCount(DEFAULT_ROSTER, setOf(DEFAULT_ROSTER.batteries.first().address.lowercase())))
    }

    @Test fun theStageChipNamesHowManyYouDisconnected() {
        assertNull(disconnectedChipText(0))
        assertEquals("1 PACK DISCONNECTED BY YOU · RECONNECT", disconnectedChipText(1))
        assertEquals("3 PACKS DISCONNECTED BY YOU · RECONNECT", disconnectedChipText(3))
    }
}
