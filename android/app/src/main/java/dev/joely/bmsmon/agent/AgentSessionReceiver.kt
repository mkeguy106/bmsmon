package dev.joely.bmsmon.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * `adb shell am broadcast -n dev.joely.bmsmon/.agent.AgentSessionReceiver --es op start --ei minutes 15`
 * (`--es op end` ends it). The manifest guards it with android.permission.DUMP, which adb's shell
 * holds. An installed app could hold it only via a privileged system image or an explicit
 * `adb shell pm grant`, so a session can only be started from inside the adb trust boundary.
 */
class AgentSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val minutes = if (intent.hasExtra("minutes")) intent.getIntExtra("minutes", 0) else null
        when (val op = parseAgentOp(intent.getStringExtra("op"), minutes)) {
            is AgentOp.Start -> {
                AgentSession.start(op.minutes, SystemClock.elapsedRealtime())
                Log.i(TAG, "session start: ${op.minutes} min")
            }
            AgentOp.End -> {
                AgentSession.end()
                Log.i(TAG, "session end")
            }
            null -> Log.w(TAG, "ignored: unknown op")
        }
    }

    private companion object { const val TAG = "AgentSession" }
}
