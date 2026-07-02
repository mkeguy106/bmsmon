package dev.joely.bmsmon

import dev.joely.bmsmon.data.normalizeApiBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTTPS-only cloud endpoint normalization (DATA-11). The platform already blocks cleartext;
 * this makes the intent explicit at the entry points (enroll UI / SettingsStore.setApiBaseUrl).
 */
class ApiBaseUrlTest {
    @Test fun httpsPassesThrough() {
        assertEquals("https://bmsmon.covert.life", normalizeApiBaseUrl("https://bmsmon.covert.life"))
    }

    @Test fun httpIsUpgradedToHttps() {
        assertEquals("https://bmsmon.covert.life", normalizeApiBaseUrl("http://bmsmon.covert.life"))
    }

    @Test fun upgradeIsCaseInsensitiveOnScheme() {
        assertEquals("https://x.example", normalizeApiBaseUrl("HTTP://x.example"))
        assertEquals("HTTPS://x.example", normalizeApiBaseUrl("HTTPS://x.example"))
    }

    @Test fun bareHostGetsHttpsPrepended() {
        assertEquals("https://bmsmon.covert.life", normalizeApiBaseUrl("bmsmon.covert.life"))
    }

    @Test fun whitespaceIsTrimmed() {
        assertEquals("https://x.example", normalizeApiBaseUrl("  http://x.example \n"))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", normalizeApiBaseUrl("   "))
    }
}
