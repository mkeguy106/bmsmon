package dev.joely.bmsmon.ui.all

import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.allTargets

/** Which link actions the All Batteries header offers — decided on the ROSTER, never the filtered rows. */
internal data class FleetHeaderActions(val disconnectAll: Boolean, val reconnectAll: Boolean)

internal fun fleetHeaderActions(roster: Roster, disabled: Set<String>): FleetHeaderActions {
    val total = roster.allTargets().size
    val off = userDisconnectedCount(roster, disabled)
    return FleetHeaderActions(disconnectAll = off < total, reconnectAll = off > 0)
}

/** Roster packs the user disconnected (the disabled set; case-insensitive). */
internal fun userDisconnectedCount(roster: Roster, disabled: Set<String>): Int {
    val off = disabled.map { it.uppercase() }.toSet()
    return roster.allTargets().count { it.address.uppercase() in off }
}

/** The persistent stage line for user-disconnected packs (UI-28) — they no longer alert. */
internal fun disconnectedChipText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 PACK DISCONNECTED BY YOU · RECONNECT"
    else -> "$count PACKS DISCONNECTED BY YOU · RECONNECT"
}
