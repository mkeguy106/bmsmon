package dev.joely.bmsmon.model

/** One battery within a base. */
data class BmsTarget(val address: String, val name: String)

/** A wheelchair base = two batteries (A/B), designated by manufacture year. */
data class BatteryGroup(
    val id: String,      // year, used as stable key
    val label: String,   // shown in the UI
    val a: BmsTarget,
    val b: BmsTarget,
) {
    val targets get() = listOf(a, b)
}

/** All known bases, two batteries each (addresses match the bmsmon aliases). */
val ALL_GROUPS: List<BatteryGroup> = listOf(
    BatteryGroup(
        "2012", "2012",
        BmsTarget("C8:47:80:15:67:44", "2012 · A"),  // R-12100BNNA70-A02214
        BmsTarget("C8:47:80:15:62:1B", "2012 · B"),  // R-12100BNNA70-A02345
    ),
    BatteryGroup(
        "2016", "2016",
        BmsTarget("C8:47:80:15:DB:13", "2016 · A"),  // R-12100BNNA70-A03902
        BmsTarget("C8:47:80:15:25:9A", "2016 · B"),  // R-12100BNNA70-A03727
    ),
    BatteryGroup(
        "2023", "2023",
        BmsTarget("C8:47:80:46:0A:D6", "2023 · A"),  // R-12100BNNA70-B02371
        BmsTarget("C8:47:80:45:90:FB", "2023 · B"),  // R-12100BNNA70-B02375
    ),
    BatteryGroup(
        "2024", "2024",
        BmsTarget("C8:47:80:15:07:DE", "2024 · A"),  // R-12100BNNA70-A02285
        BmsTarget("C8:47:80:15:25:01", "2024 · B"),  // R-12100BNNA70-A02402
    ),
)

const val DEFAULT_GROUP_ID = "2012"  // daily driver

fun groupById(id: String): BatteryGroup =
    ALL_GROUPS.firstOrNull { it.id == id } ?: ALL_GROUPS.first()

/** Demo (offline) telemetry seeds named for the given base. */
fun demoFor(group: BatteryGroup): List<Telemetry> = listOf(
    Telemetry(group.a.name, soc = 46f, powerW = 20.6f, current = 0.33f, voltage = 24.8f, capacityAh = 85f, cellV = 3.71f, temp = 23.7f),
    Telemetry(group.b.name, soc = 54f, powerW = 36.9f, current = 2.37f, voltage = 25.1f, capacityAh = 88f, cellV = 3.76f, temp = 22.6f),
)
