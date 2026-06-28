package dev.joely.bmsmon.model

/** One battery within a group, as a monitoring target (alias shown in the UI). */
data class BmsTarget(val address: String, val name: String)

/** A derived view of a group: its id, label, and current member targets (0..N). */
data class BatteryGroup(
    val id: String,
    val label: String,
    val targets: List<BmsTarget>,
)

/** Demo (offline) telemetry seeds for the given group's packs. */
fun demoFor(group: BatteryGroup): List<Telemetry> {
    val seeds = listOf(
        Telemetry("", soc = 46f, powerW = 20.6f, current = 0.33f, voltage = 24.8f, capacityAh = 85f, cellV = 3.71f, temp = 23.7f),
        Telemetry("", soc = 54f, powerW = 36.9f, current = 2.37f, voltage = 25.1f, capacityAh = 88f, cellV = 3.76f, temp = 22.6f),
    )
    if (group.targets.isEmpty()) {
        return seeds.mapIndexed { i, s -> s.copy(name = "${group.label} · ${'A' + i}") }
    }
    return group.targets.mapIndexed { i, t -> seeds[i % seeds.size].copy(name = t.name) }
}
