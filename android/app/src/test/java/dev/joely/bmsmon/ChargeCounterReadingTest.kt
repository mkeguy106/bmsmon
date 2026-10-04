package dev.joely.bmsmon

import dev.joely.bmsmon.power.chargeMahOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeCounterReadingTest {
    @Test fun extraWinsAndConvertsToMah() {
        assertEquals(3146, chargeMahOf(3_146_500) { error("property must not be read") })
    }

    @Test fun missingExtraFallsBackToProperty() {
        assertEquals(2500, chargeMahOf(Int.MIN_VALUE) { 2_500_900L })
        assertEquals(2500, chargeMahOf(0) { 2_500_000L })
        assertEquals(2500, chargeMahOf(-5) { 2_500_000L })
    }

    @Test fun unavailableEverywhereIsNull() {
        assertNull(chargeMahOf(Int.MIN_VALUE) { Long.MIN_VALUE })
        assertNull(chargeMahOf(0) { 0L })
        assertNull(chargeMahOf(0) { null })
    }

    @Test fun oversizedPropertyIsClampedNotWrapped() {
        assertEquals(Int.MAX_VALUE, chargeMahOf(0) { Long.MAX_VALUE })
    }
}
