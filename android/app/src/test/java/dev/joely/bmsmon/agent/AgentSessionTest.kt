package dev.joely.bmsmon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentSessionTest {

    @Before fun reset() = AgentSession.end()

    @Test fun minutesClamp() {
        assertEquals(AgentOp.Start(15), parseAgentOp("start", null))
        assertEquals(AgentOp.Start(15), parseAgentOp("start", 0))
        assertEquals(AgentOp.Start(15), parseAgentOp("start", -5))
        assertEquals(AgentOp.Start(60), parseAgentOp("start", 999))
        assertEquals(AgentOp.Start(7), parseAgentOp("start", 7))
    }

    @Test fun endParses() = assertEquals(AgentOp.End, parseAgentOp("end", null))

    @Test fun unknownOpIsIgnored() {
        assertNull(parseAgentOp("stop", 5))
        assertNull(parseAgentOp(null, 5))
    }

    @Test fun activeUntilDeadline() {
        assertTrue(agentActive(1000, 999))
        assertFalse(agentActive(1000, 1000))
        assertFalse(agentActive(null, 0))
    }

    @Test fun startSetsDeadlineAndExtendReplacesIt() {
        AgentSession.start(15, 0)
        assertEquals(900_000L, AgentSession.deadline.value)
        AgentSession.start(15, 60_000)
        assertEquals(960_000L, AgentSession.deadline.value)
    }

    /** An extend followed by the first session's timer firing must not end the new session. */
    @Test fun expireOnlyEndsTheMatchingDeadline() {
        AgentSession.start(15, 0)
        AgentSession.start(15, 60_000)
        AgentSession.expire(900_000)
        assertEquals(960_000L, AgentSession.deadline.value)
        AgentSession.expire(960_000)
        assertNull(AgentSession.deadline.value)
    }

    @Test fun endClears() {
        AgentSession.start(5, 0)
        AgentSession.end()
        assertNull(AgentSession.deadline.value)
    }

    @Test fun formatsRemaining() {
        assertEquals("00:00", formatAgentRemaining(0))
        assertEquals("00:00", formatAgentRemaining(-5))
        assertEquals("01:01", formatAgentRemaining(61_999))
        assertEquals("15:00", formatAgentRemaining(900_000))
    }
}
