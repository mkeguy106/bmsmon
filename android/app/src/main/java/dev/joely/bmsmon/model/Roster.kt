package dev.joely.bmsmon.model

/** One battery in the roster. [address] (MAC, uppercase) is the immutable identity / dedup key. */
data class Battery(
    val address: String,
    val advertisedName: String,  // "real name" from BLE scan; immutable
    val alias: String,           // editable display name
    val groupId: String? = null, // null = ungrouped
)

/** A user-defined group; holds 0..N batteries. */
data class Group(val id: String, val name: String)

/** The full editable roster: all batteries and all groups. */
data class Roster(
    val batteries: List<Battery> = emptyList(),
    val groups: List<Group> = emptyList(),
)

/** Default daily-driver group id. */
const val DEFAULT_GROUP_ID = "2012"

/** Canonical seed roster (the deployed setup). Single source of truth for first-run + restore. */
val DEFAULT_ROSTER: Roster = Roster(
    groups = listOf(
        Group("2012", "2012"),
        Group("2016", "2016"),
        Group("2023", "2023"),
        Group("2024", "2024"),
    ),
    batteries = listOf(
        Battery("C8:47:80:15:67:44", "R-12100BNNA70-A02214", "2012 · A", "2012"),
        Battery("C8:47:80:15:62:1B", "R-12100BNNA70-A02345", "2012 · B", "2012"),
        Battery("C8:47:80:15:DB:13", "R-12100BNNA70-A03902", "2016 · A", "2016"),
        Battery("C8:47:80:15:25:9A", "R-12100BNNA70-A03727", "2016 · B", "2016"),
        Battery("C8:47:80:46:0A:D6", "R-12100BNNA70-B02371", "2023 · A", "2023"),
        Battery("C8:47:80:45:90:FB", "R-12100BNNA70-B02375", "2023 · B", "2023"),
        Battery("C8:47:80:15:07:DE", "R-12100BNNA70-A02285", "2024 · A", "2024"),
        Battery("C8:47:80:15:25:01", "R-12100BNNA70-A02402", "2024 · B", "2024"),
    ),
)

// --- derived views over the roster (replace the old global ALL_GROUPS helpers) ---

fun Roster.batteryAt(address: String): Battery? =
    batteries.firstOrNull { it.address.equals(address, ignoreCase = true) }

private fun Roster.targetsFor(groupId: String): List<BmsTarget> =
    batteries.filter { it.groupId == groupId }.map { BmsTarget(it.address, it.alias) }

fun Roster.groupById(id: String): BatteryGroup? =
    groups.firstOrNull { it.id == id }?.let { BatteryGroup(it.id, it.name, targetsFor(it.id)) }

/** All groups as [BatteryGroup] views, in roster order (the old `ALL_GROUPS`). */
fun Roster.groupViews(): List<BatteryGroup> =
    groups.map { BatteryGroup(it.id, it.name, targetsFor(it.id)) }

fun Roster.groupOf(address: String): BatteryGroup? =
    batteryAt(address)?.groupId?.let { groupById(it) }

/** Every battery as a [BmsTarget] (the full monitoring set). */
fun Roster.allTargets(): List<BmsTarget> =
    batteries.map { BmsTarget(it.address, it.alias) }

fun Roster.targetFor(address: String): BmsTarget? =
    batteryAt(address)?.let { BmsTarget(it.address, it.alias) }

// --- pure mutations (each returns a new Roster) ---

/** Add a battery if its MAC is not already present (case-insensitive). Alias defaults to the name. */
fun Roster.addBattery(address: String, advertisedName: String): Roster {
    val a = address.trim().uppercase()
    if (batteries.any { it.address.equals(a, ignoreCase = true) }) return this
    return copy(batteries = batteries + Battery(a, advertisedName, advertisedName, null))
}

fun Roster.removeBattery(address: String): Roster =
    copy(batteries = batteries.filterNot { it.address.equals(address, ignoreCase = true) })

fun Roster.renameBattery(address: String, alias: String): Roster =
    copy(batteries = batteries.map { if (it.address.equals(address, ignoreCase = true)) it.copy(alias = alias) else it })

fun Roster.assignGroup(address: String, groupId: String?): Roster =
    copy(batteries = batteries.map { if (it.address.equals(address, ignoreCase = true)) it.copy(groupId = groupId) else it })

/** Create a new group with a generated id; returns the new roster and the new group's id. */
fun Roster.addGroup(name: String): Pair<Roster, String> {
    val id = newGroupId()
    return copy(groups = groups + Group(id, name)) to id
}

fun Roster.renameGroup(groupId: String, name: String): Roster =
    copy(groups = groups.map { if (it.id == groupId) it.copy(name = name) else it })

/** Deterministic fresh id ("g1", "g2", …) that doesn't collide with existing group ids. */
private fun Roster.newGroupId(): String {
    val existing = groups.map { it.id }.toSet()
    var n = 1
    while ("g$n" in existing) n++
    return "g$n"
}
