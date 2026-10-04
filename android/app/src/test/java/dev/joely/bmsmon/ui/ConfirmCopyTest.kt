package dev.joely.bmsmon.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-22/UI-28: every confirmation says what happens and what is lost. */
class ConfirmCopyTest {
    @Test fun stoppingSaysTheAlertsStop() {
        assertTrue(Confirmations.stopMonitoring.body.contains("alerts stop"))
    }

    @Test fun clearingDataSaysWhatIsLostForGood() {
        val body = Confirmations.clearData.body
        assertTrue(body.contains("can't be undone"))
        assertTrue(body.contains("re-sent"))
        assertTrue(body.contains("history") || body.contains("History"))
        assertTrue(body.contains("queued for upload"))
        assertTrue(!body.contains("range"))
    }

    @Test fun disconnectingSaysItSurvivesARestart() {
        assertTrue(Confirmations.disconnectStagePack("2012 · A").body.contains("even after a restart"))
        assertTrue(Confirmations.disconnectAll(8).title.contains("8"))
    }

    @Test fun forgettingSaysANewCodeIsNeeded() {
        assertTrue(Confirmations.forgetDevice.body.contains("new code"))
    }
}
