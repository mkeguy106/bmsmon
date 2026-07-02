package dev.joely.bmsmon

import dev.joely.bmsmon.ble.BmsProtocol
import dev.joely.bmsmon.ble.profile.ProfileRegistry
import dev.joely.bmsmon.ble.profile.RedodoBekenProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BatteryProfileTest {
    @Test fun selectsRedodoByPrefix() {
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("R-12100BNNA70-A02402"))
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("LT-12100"))
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("PQ-12100"))
        assertSame(RedodoBekenProfile, ProfileRegistry.profileFor("ss-12100")) // case-insensitive
    }

    @Test fun rejectsUnknownAndBlank() {
        assertNull(ProfileRegistry.profileFor("MyHeadphones"))
        assertNull(ProfileRegistry.profileFor("X-12100"))
        assertNull(ProfileRegistry.profileFor(null))
        assertNull(ProfileRegistry.profileFor(""))
    }

    @Test fun prebuiltFramesMatchTheClosedEnum() {
        // The profile must expose the SAME bytes the closed ReadCommand enum produces — no other
        // opcodes. statusFrame is the ONLY command frame a profile exposes (BLE-7 removed the dead
        // firmwareFrame), and it must pass the write-time safety gate.
        assertEquals(
            BmsProtocol.frame(BmsProtocol.ReadCommand.STATUS).toList(),
            RedodoBekenProfile.statusFrame.toList(),
        )
        assertEquals(true, BmsProtocol.isSafeStatusFrame(RedodoBekenProfile.statusFrame))
    }

    @Test fun connectionDefaultsMatchSpec() {
        assertEquals(8, RedodoBekenProfile.maxHeldConnections)
        assertEquals(1500L, RedodoBekenProfile.stagePollMs)
        assertEquals(10_000L, RedodoBekenProfile.slowPollMs)
        assertEquals(5_000L, RedodoBekenProfile.backoff.baseMs)
        assertEquals(120_000L, RedodoBekenProfile.backoff.capMs)
        assertEquals(true, RedodoBekenProfile.writeWithResponse)
    }
}
