package dev.joely.bmsmon.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowReenrollTest {
    @Test fun gate() {
        assertTrue(showReenroll(enrolled = false, keyMissing = false, authFailed = false))
        assertTrue(showReenroll(true, keyMissing = true, authFailed = false))
        assertTrue(showReenroll(true, keyMissing = false, authFailed = true))
        assertFalse(showReenroll(true, keyMissing = false, authFailed = false))
    }
}
