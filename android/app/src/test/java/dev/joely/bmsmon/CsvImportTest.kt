package dev.joely.bmsmon

import dev.joely.bmsmon.data.parseCsvLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvImportTest {
    @Test fun parsesTelemetryRow() {
        val row = "1782605607426,2016 · B,C8:47:80:15:25:9A,Charging,64,7.948,107.29,13.499,0"
        val s = parseCsvLine(row)!!
        assertEquals("C8:47:80:15:25:9A", s.address)
        assertEquals(1782605607426L, s.tsMs)
        assertEquals("Charging", s.state)
        assertEquals(64f, s.soc!!, 0.01f)
        assertEquals(7.948f, s.currentA!!, 0.001f)
        assertEquals(107.29f, s.powerW!!, 0.01f)
        assertEquals(13.499f, s.voltageV!!, 0.001f)
        assertEquals(false, s.regen)
        assertNull(s.linkEvent)
        assertNull(s.soh)        // not present in the CSV
    }

    @Test fun parsesRegenFlag() {
        val row = "1782663972080,2012 · A,C8:47:80:15:67:44,Discharging,87,-22.324,297.29,13.317,1"
        assertEquals(true, parseCsvLine(row)!!.regen)
    }

    @Test fun parsesLinkEventRow() {
        val row = "1782687206267,2023 · B,C8:47:80:45:90:FB,Disconnected,,,,,0"
        val s = parseCsvLine(row)!!
        assertEquals("Disconnected", s.linkEvent)
        assertNull(s.soc)
        assertNull(s.currentA)
    }

    @Test fun skipsHeaderAndJunk() {
        assertNull(parseCsvLine("timestamp_ms,name,address,state,soc,current_a,power_w,voltage_v,regen"))
        assertNull(parseCsvLine(""))
        assertNull(parseCsvLine("garbage,row"))
    }
}
