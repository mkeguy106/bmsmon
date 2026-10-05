package dev.joely.bmsmon.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAlertTest {
    private val unknown = PhoneAlertLocal()

    @Test fun adoptsTheServerWhenNotDirty() {
        val r = reconcilePhoneAlert(PhoneAlertLocal(50, true, 900, dirty = false), PhoneAlertSync(75, 100))
        assertEquals(PhoneAlertLocal(75, true, 100, false), r)
    }

    @Test fun firstAnswerIsAdoptedEvenTheDefault() {
        assertEquals(PhoneAlertLocal(75, true, 0, false), reconcilePhoneAlert(unknown, PhoneAlertSync(75, 0)))
    }

    @Test fun adoptsWhenTheServerChangeIsAsNewAsTheLocalOne() {
        val local = PhoneAlertLocal(50, true, 500, dirty = true)
        assertEquals(PhoneAlertLocal(20, true, 500, false), reconcilePhoneAlert(local, PhoneAlertSync(20, 500)))
        assertEquals(PhoneAlertLocal(20, true, 600, false), reconcilePhoneAlert(local, PhoneAlertSync(20, 600)))
    }

    @Test fun keepsADirtyLocalValueThatIsNewer() {
        val local = PhoneAlertLocal(50, true, 500, dirty = true)
        assertEquals(local, reconcilePhoneAlert(local, PhoneAlertSync(20, 499)))
    }

    @Test fun offRoundTrips() {
        val r = reconcilePhoneAlert(unknown, PhoneAlertSync(null, 10))
        assertNull(r.lowPct)
        assertEquals(true, r.known)
        val kept = PhoneAlertLocal(null, true, 500, dirty = true)
        assertEquals(kept, reconcilePhoneAlert(kept, PhoneAlertSync(30, 1)))
    }

    @Test fun parsesTheBlockLeniently() {
        assertEquals(PhoneAlertSync(75, 5), parsePhoneAlert("""{"accepted":1,"phone_alert":{"low_pct":75,"updated_at_ms":5,"updated_by":"web"}}"""))
        assertEquals(PhoneAlertSync(null, 0), parsePhoneAlert("""{"phone_alert":{"low_pct":null,"updated_at_ms":0}}"""))
        assertNull(parsePhoneAlert(null))
        assertNull(parsePhoneAlert(""))
        assertNull(parsePhoneAlert("not json"))
        assertNull(parsePhoneAlert("""{"accepted":1}"""))
        assertNull(parsePhoneAlert("""{"phone_alert":{"low_pct":12,"updated_at_ms":5}}"""))
        assertNull(parsePhoneAlert("""{"phone_alert":{"low_pct":"75","updated_at_ms":5}}"""))
        assertNull(parsePhoneAlert("""{"phone_alert":{"low_pct":75}}"""))
        assertNull(parsePhoneAlert("""{"phone_alert":{"low_pct":75,"updated_at_ms":-1}}"""))
        assertNull(parsePhoneAlert("""{"phone_alert":[1]}"""))
    }

    @Test fun readsWhatAPushCarried() {
        assertEquals(PhoneAlertSent(40, 7), sentPhoneAlert("""{"a":1,"phone_low_pct":40,"phone_low_pct_changed_ms":7}"""))
        assertEquals(PhoneAlertSent(null, 7), sentPhoneAlert("""{"phone_low_pct":null,"phone_low_pct_changed_ms":7}"""))
        assertNull(sentPhoneAlert("""{"a":1}"""))
        assertNull(sentPhoneAlert("junk"))
    }

    @Test fun optionsAndLabels() {
        assertEquals(listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95), PHONE_ALERT_OPTIONS)
        assertFalse(isPhoneAlertLevel(12))
        assertEquals("—", phoneAlertLabel(unknown))
        assertEquals("Off", phoneAlertLabel(PhoneAlertLocal(null, true)))
        assertEquals("75%", phoneAlertLabel(PhoneAlertLocal(75, true)))
    }
}
