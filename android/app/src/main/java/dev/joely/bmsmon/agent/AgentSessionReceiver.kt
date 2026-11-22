package dev.joely.bmsmon.agent

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display

/**
 * `adb shell am broadcast -n dev.joely.bmsmon/.agent.AgentSessionReceiver --es op start --ei minutes 15`
 * (`--es op end` ends it). The manifest guards it with android.permission.DUMP, which adb's shell
 * holds. An installed app could hold it only via a privileged system image or an explicit
 * `adb shell pm grant`, so a session can only be started from inside the adb trust boundary.
 *
 * `--es op release-owner` is the undo for `dpm set-device-owner`: Android offers no adb command to
 * remove a device owner from a non-testOnly app, and Settings cannot either, so without this only
 * a factory reset could. Behind the same DUMP guard, so only adb can do it.
 */
class AgentSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val minutes = if (intent.hasExtra("minutes")) intent.getIntExtra("minutes", 0) else null
        when (val op = parseAgentOp(intent.getStringExtra("op"), minutes)) {
            is AgentOp.Start -> {
                // Note the screen's rotation before the pin lifts; the re-pin restores it.
                val rotation = runCatching {
                    context.getSystemService(DisplayManager::class.java)
                        ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                }.getOrNull()
                AgentSession.start(op.minutes, SystemClock.elapsedRealtime(), rotation)
                Log.i(TAG, "session start: ${op.minutes} min, rotation $rotation")
            }
            AgentOp.End -> {
                AgentSession.end()
                Log.i(TAG, "session end")
            }
            AgentOp.ReleaseOwner -> {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.clearDeviceOwnerApp(context.packageName)
                    Log.i(TAG, "device owner released")
                } else {
                    Log.w(TAG, "release-owner ignored: not the device owner")
                }
            }
            null -> Log.w(TAG, "ignored: unknown op")
        }
    }

    private companion object { const val TAG = "AgentSession" }
}
