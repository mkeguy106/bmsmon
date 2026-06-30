package dev.joely.bmsmon

import dev.joely.bmsmon.ble.delayFor
import dev.joely.bmsmon.ble.FleetPlan
import dev.joely.bmsmon.ble.planFleet
import dev.joely.bmsmon.ble.profile.BackoffSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class FleetPlannerTest {
    private val spec = BackoffSpec(baseMs = 5_000L, factor = 2, capMs = 120_000L)

    @Test fun backoffGrowsThenCaps() {
        assertEquals(0L, spec.delayFor(0))         // never failed → eligible immediately
        assertEquals(5_000L, spec.delayFor(1))     // 5s
        assertEquals(10_000L, spec.delayFor(2))    // 10s
        assertEquals(20_000L, spec.delayFor(3))    // 20s
        assertEquals(40_000L, spec.delayFor(4))    // 40s
        assertEquals(80_000L, spec.delayFor(5))    // 80s
        assertEquals(120_000L, spec.delayFor(6))   // 160s → capped at 120s
        assertEquals(120_000L, spec.delayFor(20))  // stays capped
    }
}

class FleetPlannerPlanTest {
    private fun plan(
        desired: Set<String>, stage: Set<String> = emptySet(), held: Set<String> = emptySet(),
        connecting: Set<String> = emptySet(), backoffUntil: Map<String, Long> = emptyMap(),
        heldSince: Map<String, Long> = emptyMap(), maxHeld: Int = 8, now: Long = 1000,
        stageFirst: Boolean = false,
    ) = planFleet(desired, stage, held, connecting, backoffUntil, heldSince, maxHeld, now, stageFirst)

    @org.junit.Test fun connectsAllDesiredWithinBudget() {
        val p = plan(desired = setOf("A", "B", "C"))
        org.junit.Assert.assertEquals(listOf("A", "B", "C"), p.toConnect)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toDisconnect)
    }

    @org.junit.Test fun stageConnectsFirst() {
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("C"), maxHeld = 1)
        org.junit.Assert.assertEquals(listOf("C"), p.toConnect)  // stage prioritized over A/B
    }

    @org.junit.Test fun skipsBackedOffPacks() {
        val p = plan(desired = setOf("A", "B"), backoffUntil = mapOf("A" to 5000), now = 1000)
        org.junit.Assert.assertEquals(listOf("B"), p.toConnect)  // A still backed off
    }

    @org.junit.Test fun dropsNoLongerDesired() {
        val p = plan(desired = setOf("A"), held = setOf("A", "B"))
        org.junit.Assert.assertEquals(listOf("B"), p.toDisconnect) // B disabled/removed
        org.junit.Assert.assertEquals(emptyList<String>(), p.toConnect)
    }

    @org.junit.Test fun respectsBudgetNoOverflowRotationWhenFull() {
        // 2 held fill the budget; a 3rd desired waits — with maxHeld=2 it neither connects nor rotates
        // a STAGE pack out. Here both held are stage, so no rotation; toConnect empty.
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("A", "B"),
            held = setOf("A", "B"), heldSince = mapOf("A" to 1, "B" to 2), maxHeld = 2)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toConnect)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toDisconnect)
    }

    @org.junit.Test fun overflowRotatesOldestNonStageOut() {
        // Budget full with non-stage held A(old),B(new); desired also wants C. Rotate oldest non-stage (A).
        val p = plan(desired = setOf("A", "B", "C"), stage = emptySet(),
            held = setOf("A", "B"), heldSince = mapOf("A" to 1, "B" to 5), maxHeld = 2)
        org.junit.Assert.assertEquals(listOf("A"), p.toDisconnect)
        org.junit.Assert.assertEquals(listOf("C"), p.toConnect)
    }

    // --- stage-first launch barrier: only stage packs connect until they're all up ---

    @org.junit.Test fun stageFirstAdmitsOnlyStagePacks() {
        // Nothing held yet, plenty of budget, but the barrier is armed: only the stage pack connects;
        // every background pack waits.
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("C"), stageFirst = true)
        org.junit.Assert.assertEquals(listOf("C"), p.toConnect)
    }

    @org.junit.Test fun stageFirstConnectsAllStagePacksTogether() {
        // A 2-pack base (the chair): both stage packs admitted, background pack still waits.
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("B", "C"), stageFirst = true)
        org.junit.Assert.assertEquals(setOf("B", "C"), p.toConnect.toSet())
        org.junit.Assert.assertFalse("A" in p.toConnect)
    }

    @org.junit.Test fun stageFirstAdmitsNothingWhenStageBackedOff() {
        // Stage pack is backing off (flaky establishment) — admit nothing rather than letting
        // background packs jump the queue.
        val p = plan(desired = setOf("A", "B", "C"), stage = setOf("C"),
            backoffUntil = mapOf("C" to 5000), now = 1000, stageFirst = true)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toConnect)
    }

    @org.junit.Test fun stageFirstWithEmptyStageAdmitsNothing() {
        // Barrier armed but stage not yet known (setStage hasn't landed): hold everyone for the tick.
        val p = plan(desired = setOf("A", "B"), stage = emptySet(), stageFirst = true)
        org.junit.Assert.assertEquals(emptyList<String>(), p.toConnect)
    }
}
