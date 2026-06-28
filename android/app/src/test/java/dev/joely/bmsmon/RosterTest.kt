package dev.joely.bmsmon

import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupOf
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
}
