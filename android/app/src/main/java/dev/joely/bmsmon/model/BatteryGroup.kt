package dev.joely.bmsmon.model

/** One battery within a group, as a monitoring target (alias shown in the UI). */
data class BmsTarget(val address: String, val name: String)

/** A derived view of a group: its id, label, and current member targets (0..N). */
data class BatteryGroup(
    val id: String,
    val label: String,
    val targets: List<BmsTarget>,
)
