package dev.joely.bmsmon.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DATA-22: the Cloud sync page shows the skip count only once something was actually skipped. */
class ServerFaultSkipsLineTest {

    @Test fun noLineUntilASampleIsSkipped() {
        assertNull(serverFaultSkipsLine(0))
    }

    @Test fun countsAreWorded() {
        assertEquals("1 sample the server could not store was skipped", serverFaultSkipsLine(1))
        assertEquals("3 samples the server could not store were skipped", serverFaultSkipsLine(3))
    }
}
