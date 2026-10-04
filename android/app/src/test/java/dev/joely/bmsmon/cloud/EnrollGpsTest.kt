package dev.joely.bmsmon.cloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Send GPS location" across an enrollment. A first enrollment turns it on (the default with cloud sync);
 * re-enrolling an enrolled phone, to fix a missing key or a rejected sign-in, restores uploading and
 * leaves what is shared exactly as the user set it.
 */
class EnrollGpsTest {
    @Test fun aFirstEnrollmentTurnsGpsOn() {
        assertTrue(gpsAfterEnroll(wasEnrolled = false, current = false))
        assertTrue(gpsAfterEnroll(wasEnrolled = false, current = true))
    }

    @Test fun reEnrollingKeepsGpsOff() {
        assertFalse(gpsAfterEnroll(wasEnrolled = true, current = false))
    }

    @Test fun reEnrollingKeepsGpsOn() {
        assertTrue(gpsAfterEnroll(wasEnrolled = true, current = true))
    }

    // The ViewModel decides through gpsAfterEnroll and persists that value; it never turns GPS on outright.
    @Test fun enrollPersistsTheDecidedValue() {
        val src = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "dev/joely/bmsmon/BatteryViewModel.kt") }
            .first { it.isFile }
            .readText()
        val enroll = src.substringAfter("fun enroll(").substringBefore("fun forgetDevice(")
        assertFalse(enroll.contains("setGpsEnabled(true)"))
        assertFalse(enroll.contains("gpsEnabled = true"))
        assertTrue(enroll.contains("gpsAfterEnroll("))
        assertEquals(1, Regex("store\\.setGpsEnabled\\(gps\\)").findAll(enroll).count())
        assertTrue(enroll.contains("gpsEnabled = gps"))
    }
}
