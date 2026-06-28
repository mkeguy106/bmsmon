package dev.joely.bmsmon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {

    // --- hysteresis ---
    @Test fun brightGoesLight() {
        // threshold 300 → high = 390; 500 lux is above → Light
        assertEquals(Mode.Light, resolveAutoMode(500f, 300f, Mode.Dark))
    }

    @Test fun dimGoesDark() {
        // threshold 300 → low = ~231; 100 lux is below → Dark
        assertEquals(Mode.Dark, resolveAutoMode(100f, 300f, Mode.Light))
    }

    @Test fun insideDeadbandHoldsCurrent() {
        // 300 lux sits between low(231) and high(390) → keep whatever we are
        assertEquals(Mode.Dark, resolveAutoMode(300f, 300f, Mode.Dark))
        assertEquals(Mode.Light, resolveAutoMode(300f, 300f, Mode.Light))
    }

    // --- debounce ---
    @Test fun debounceHoldsUntilSustained() {
        val t0 = 1_000_000L
        // candidate differs but not sustained long enough → keep current
        assertEquals(Mode.Dark, debouncedMode(Mode.Dark, Mode.Light, candidateSince = t0, now = t0 + 1000, debounceMs = 2500))
        // one ms before the window closes → still hold current
        assertEquals(Mode.Dark, debouncedMode(Mode.Dark, Mode.Light, candidateSince = t0, now = t0 + 2499, debounceMs = 2500))
        // sustained past the window → commit candidate
        assertEquals(Mode.Light, debouncedMode(Mode.Dark, Mode.Light, candidateSince = t0, now = t0 + 2500, debounceMs = 2500))
    }

    @Test fun debounceNoOpWhenCandidateEqualsCurrent() {
        assertEquals(Mode.Light, debouncedMode(Mode.Light, Mode.Light, candidateSince = 0, now = 9_999_999, debounceMs = 2500))
    }

    // --- migration ---
    @Test fun legacyMigration() {
        assertEquals(Appearance.System, legacyAppearance(manualMode = false, darkMode = false))
        assertEquals(Appearance.System, legacyAppearance(manualMode = false, darkMode = true))
        assertEquals(Appearance.Dark, legacyAppearance(manualMode = true, darkMode = true))
        assertEquals(Appearance.Light, legacyAppearance(manualMode = true, darkMode = false))
    }

    // --- slider log mapping ---
    @Test fun luxFractionRoundTrips() {
        assertEquals(0f, luxToFraction(AUTO_LUX_MIN), 0.001f)
        assertEquals(1f, luxToFraction(AUTO_LUX_MAX), 0.001f)
        // midpoint fraction is the geometric mean (log scale)
        assertEquals(200f, fractionToLux(0.5f), 1f)   // sqrt(20*2000) = 200
        assertEquals(300f, fractionToLux(luxToFraction(300f)), 0.5f)
    }

    @Test fun luxFractionClampsOutOfRange() {
        assertTrue(luxToFraction(1f) in 0f..0.001f)      // below min clamps to 0
        assertTrue(luxToFraction(99_999f) in 0.999f..1f) // above max clamps to 1
    }
}
