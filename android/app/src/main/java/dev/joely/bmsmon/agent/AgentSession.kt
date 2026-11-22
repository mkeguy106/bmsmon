package dev.joely.bmsmon.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An "agent session": a bounded window in which an adb-driven agent (phonedriver) may use other
 * apps, so bmsmon lifts its screen pin and does not re-pin itself until the session ends.
 * Started and ended only by [AgentSessionReceiver], which only adb's shell can reach.
 * Spec: docs/superpowers/specs/2026-11-13-agent-session-unpin-design.md.
 *
 * Memory only, on purpose: a process restart ends the session and the ordinary on-resume
 * re-pin takes over, so a crash can never leave the phone unpinned.
 */
const val AGENT_DEFAULT_MIN = 15
const val AGENT_MAX_MIN = 60

sealed interface AgentOp {
    data class Start(val minutes: Int) : AgentOp
    data object End : AgentOp

    /** Give up device ownership (the undo for `dpm set-device-owner`); see [AgentSessionReceiver]. */
    data object ReleaseOwner : AgentOp
}

/** `start` with missing or < 1 minutes means [AGENT_DEFAULT_MIN]; more than [AGENT_MAX_MIN] clamps. Anything else is ignored (null). */
fun parseAgentOp(op: String?, minutes: Int?): AgentOp? = when (op) {
    "start" -> AgentOp.Start(
        when {
            minutes == null || minutes < 1 -> AGENT_DEFAULT_MIN
            else -> minutes.coerceAtMost(AGENT_MAX_MIN)
        },
    )
    "end" -> AgentOp.End
    "release-owner" -> AgentOp.ReleaseOwner
    else -> null
}

/** Whether a session with this deadline (elapsedRealtime ms) is still running at [nowMs]. */
fun agentActive(deadlineMs: Long?, nowMs: Long): Boolean = deadlineMs != null && nowMs < deadlineMs

/** Remaining time as `mm:ss`, floored, never negative. */
fun formatAgentRemaining(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%02d:%02d".format(s / 60, s % 60)
}

object AgentSession {
    private val _deadline = MutableStateFlow<Long?>(null)

    /** The running session's deadline on the elapsedRealtime clock, or null when none runs. */
    val deadline: StateFlow<Long?> = _deadline.asStateFlow()

    // The phone screen's rotation (Surface.ROTATION_*) just before the session lifted the pin.
    // The agent's apps can turn the screen while the pin is down, so the re-pin puts this back.
    @Volatile private var rotation: Int? = null

    /**
     * Starts a session, or restarts the timer of the running one (extend). A new session notes
     * [displayRotation]; an extend keeps the rotation from before the first start.
     */
    fun start(minutes: Int, nowMs: Long, displayRotation: Int? = null) {
        if (_deadline.value == null) rotation = displayRotation
        _deadline.value = nowMs + minutes * 60_000L
    }

    /** The rotation noted when the last session started, handed out once (to the re-pin). */
    fun takeRotation(): Int? = rotation.also { rotation = null }

    fun end() {
        _deadline.value = null
    }

    /** Ends the session only if it is still the one with [deadlineMs], so a stale timer can't end an extended session. */
    fun expire(deadlineMs: Long) {
        _deadline.compareAndSet(deadlineMs, null)
    }
}
