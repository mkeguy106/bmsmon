package dev.joely.bmsmon

import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.addBattery
import dev.joely.bmsmon.model.addGroup
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.assignGroup
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.model.removeBattery
import dev.joely.bmsmon.model.renameBattery
import dev.joely.bmsmon.model.renameGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RosterTest {
    @Test fun seedHasFourGroupsOfTwo() {
        assertEquals(listOf("2012", "2016", "2023", "2024"), DEFAULT_ROSTER.groups.map { it.id })
        DEFAULT_ROSTER.groups.forEach { g ->
            assertEquals(2, DEFAULT_ROSTER.batteries.count { it.groupId == g.id })
        }
        assertEquals(8, DEFAULT_ROSTER.batteries.size)
    }

    @Test fun seedAliasesAndAddressesExact() {
        val b = DEFAULT_ROSTER.batteries.first { it.address == "C8:47:80:15:25:01" }
        assertEquals("2024 · B", b.alias)
        assertEquals("R-12100BNNA70-A02402", b.advertisedName)
        assertEquals("2024", b.groupId)
        assertEquals(DEFAULT_GROUP_ID, "2012")
    }

    @Test fun derivedGroupViewHasAliasTargets() {
        val g = DEFAULT_ROSTER.groupById("2012")!!
        assertEquals("2012", g.label)
        assertEquals(listOf("2012 · A", "2012 · B"), g.targets.map { it.name })
    }

    @Test fun groupOfAndAllTargets() {
        assertEquals("2016", DEFAULT_ROSTER.groupOf("C8:47:80:15:DB:13")!!.id)
        assertNull(DEFAULT_ROSTER.groupOf("00:00:00:00:00:00"))
        assertEquals(8, DEFAULT_ROSTER.allTargets().size)
    }

    @Test fun addBatteryDedupsByMac() {
        val r = DEFAULT_ROSTER
            .addBattery("AA:BB:CC:DD:EE:FF", "R-12100-NEW")
            .addBattery("aa:bb:cc:dd:ee:ff", "R-12100-NEW")
        assertEquals(9, r.batteries.size)
        val added = r.batteryAt("AA:BB:CC:DD:EE:FF")!!
        assertEquals("R-12100-NEW", added.alias)
        assertNull(added.groupId)
    }

    @Test fun removeRenameAndRegroup() {
        var r = DEFAULT_ROSTER.removeBattery("C8:47:80:15:25:01")
        assertEquals(7, r.batteries.size)
        r = r.renameBattery("C8:47:80:15:07:DE", "Chair spare")
        assertEquals("Chair spare", r.batteryAt("C8:47:80:15:07:DE")!!.alias)
        r = r.assignGroup("C8:47:80:15:07:DE", "2012")
        assertEquals("2012", r.batteryAt("C8:47:80:15:07:DE")!!.groupId)
        r = r.assignGroup("C8:47:80:15:07:DE", null)
        assertNull(r.batteryAt("C8:47:80:15:07:DE")!!.groupId)
    }

    @Test fun addGroupReturnsNewIdAndRename() {
        val (r1, id) = DEFAULT_ROSTER.addGroup("Garage")
        assertEquals(5, r1.groups.size)
        assertEquals("Garage", r1.groups.first { it.id == id }.name)
        val r2 = r1.renameGroup(id, "Shed")
        assertEquals("Shed", r2.groups.first { it.id == id }.name)
    }
}
