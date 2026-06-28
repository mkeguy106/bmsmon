# Editable Battery & Group Roster — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app's batteries and groups user-editable (add via BLE scan, remove, rename, regroup, rename groups) with a per-battery detail page, replacing the hardcoded `ALL_GROUPS` constant with a persisted, reactive roster.

**Architecture:** Introduce a `Roster` (flat list of `Battery` records + `Group` records) as the single source of truth. `BatteryGroup`/`BmsTarget` become *derived views* over the roster so existing stage/monitoring logic keeps working. The roster lives in `UiState`, is persisted to DataStore as JSON, and seeds from the current hardcoded values on first launch. UI gains a scan sheet, a long-press action menu, swipe-to-delete, and a detail screen.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, AndroidX DataStore (Preferences), `org.json`, kotlinx.coroutines, JUnit 4 (JVM unit tests only — no Robolectric/instrumentation).

## Global Constraints

- **Preserve the current roster exactly.** Seed = 4 groups of 2 with these exact group ids/labels, aliases, MACs, and advertised names; daily driver `2012`:
  - `2012`: `2012 · A`=`C8:47:80:15:67:44` (`R-12100BNNA70-A02214`), `2012 · B`=`C8:47:80:15:62:1B` (`R-12100BNNA70-A02345`)
  - `2016`: `2016 · A`=`C8:47:80:15:DB:13` (`R-12100BNNA70-A03902`), `2016 · B`=`C8:47:80:15:25:9A` (`R-12100BNNA70-A03727`)
  - `2023`: `2023 · A`=`C8:47:80:46:0A:D6` (`R-12100BNNA70-B02371`), `2023 · B`=`C8:47:80:45:90:FB` (`R-12100BNNA70-B02375`)
  - `2024`: `2024 · A`=`C8:47:80:15:07:DE` (`R-12100BNNA70-A02285`), `2024 · B`=`C8:47:80:15:25:01` (`R-12100BNNA70-A02402`)
- **Safety: read-only only.** Scanning surfaces compatible BMS prefixes only; never connect to unknown hardware; never add/send any new/destructive command byte. This feature only edits a local roster and reads telemetry.
- **Addresses (MACs) are normalized uppercase** and are the identity/dedup key.
- **No new test infra.** Unit-test pure JVM logic only (model + roster mutations + scan-name filter). JSON persistence and all Compose UI are verified by build + manual on-device check (matches the existing codebase, which does not unit-test `org.json` code).
- **Commit message rule (repo CLAUDE.md):** never mention AI/Claude/automated generation, no `Co-Authored-By`.
- Build from `android/`: `./gradlew :app:testDebugUnitTest` (unit tests), `./gradlew :app:assembleDebug` (compile).

## File Structure

| File | Responsibility |
|------|----------------|
| `model/Roster.kt` (new) | `Battery`, `Group`, `Roster`, `DEFAULT_ROSTER`, `DEFAULT_GROUP_ID`, derived views (`groupById`, `groupViews`, `groupOf`, `allTargets`, `targetFor`, `batteryAt`), and pure mutations |
| `model/BatteryGroup.kt` (modify) | `BmsTarget`, `BatteryGroup` (now `targets: List<BmsTarget>`), `demoFor` — drop `ALL_GROUPS`, `groupById`, `groupForAddress`, `DEFAULT_GROUP_ID` |
| `model/Fleet.kt` (modify) | `StageInputs` gains `groups`; `resolveStage` reads `i.groups`; `StageTarget.addresses(roster)`; drop `groupForAddress` |
| `data/SettingsStore.kt` (modify) | persist/load `Roster` JSON |
| `ble/BleScanner.kt` (new) | `isCompatibleBmsName`, `DiscoveredDevice`, `BleScanner` (compatible-only scan) |
| `ble/BmsRepository.kt` (modify) | `setTargets(...)` to update the polled set live |
| `BatteryViewModel.kt` (modify) | `roster` in `UiState`; roster mutations; re-resolve + retarget; route detail/scan |
| `ui/scan/ScanSheet.kt` (new) | scan modal: discovered list, known-dimmed, quick-add |
| `ui/detail/BatteryDetailScreen.kt` (new) | per-battery detail (identity, cells, telemetry, graph placeholder) |
| `ui/all/AllBatteriesScreen.kt` (modify) | header `+`, long-press menu actions, swipe-delete, single-tap → detail |
| `ui/home/StageScreen.kt` (modify) | empty-roster `+`; horizontal scroll for 3+ packs |
| `ui/home/HomeScreen.kt` (modify) | thread new callbacks; open scan sheet |
| `ui/App.kt` (modify) | route `Screen.Detail`; host scan sheet |
| `test/.../RosterTest.kt` (new) | seed-match + mutation tests |
| `test/.../FleetLogicTest.kt` (modify) | build from `DEFAULT_ROSTER`; pass `groups` |

---

### Task 1: Roster model + DEFAULT_ROSTER + derived views

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/model/Roster.kt`
- Test: `app/src/test/java/dev/joely/bmsmon/RosterTest.kt`

**Interfaces:**
- Produces: `data class Battery(address, advertisedName, alias, groupId: String?)`, `data class Group(id, name)`, `data class Roster(batteries, groups)`, `val DEFAULT_ROSTER`, `const val DEFAULT_GROUP_ID`, and extensions `Roster.groupById(id): BatteryGroup?`, `Roster.groupViews(): List<BatteryGroup>`, `Roster.groupOf(addr): BatteryGroup?`, `Roster.batteryAt(addr): Battery?`, `Roster.allTargets(): List<BmsTarget>`, `Roster.targetFor(addr): BmsTarget?`. Depends on `BatteryGroup`/`BmsTarget` from `BatteryGroup.kt` (Task 3 changes their shape — write this task's code against the *new* shape `BatteryGroup(id, label, targets)`; build happens after Task 3).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/joely/bmsmon/RosterTest.kt`:

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.model.DEFAULT_GROUP_ID
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RosterTest {
    @Test fun seedHasFourGroupsOfTwo() {
        assertEquals(listOf("2012", "2016", "2023", "2024"), DEFAULT_ROSTER.groups.map { it.id })
        DEFAULT_ROSTER.groups.forEach { g ->
            assertEquals(2, DEFAULT_ROSTER.batteries.count { it.groupId == g.id })
        }
        assertEquals(8, DEFAULT_ROSTER.batteries.size)
    }

    @Test fun seedAliasesAndAddressesExact() {
        val b = DEFAULT_ROSTER.batteries.first { it.address == "C8:47:80:15:25:01" }
        assertEquals("2024 · B", b.alias)
        assertEquals("R-12100BNNA70-A02402", b.advertisedName)
        assertEquals("2024", b.groupId)
        assertEquals(DEFAULT_GROUP_ID, "2012")
    }

    @Test fun derivedGroupViewHasAliasTargets() {
        val g = DEFAULT_ROSTER.groupById("2012")!!
        assertEquals("2012", g.label)
        assertEquals(listOf("2012 · A", "2012 · B"), g.targets.map { it.name })
    }

    @Test fun groupOfAndAllTargets() {
        assertEquals("2016", DEFAULT_ROSTER.groupOf("C8:47:80:15:DB:13")!!.id)
        assertNull(DEFAULT_ROSTER.groupOf("00:00:00:00:00:00"))
        assertEquals(8, DEFAULT_ROSTER.allTargets().size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RosterTest"`
Expected: FAIL (unresolved references — `Roster.kt` doesn't exist yet).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/dev/joely/bmsmon/model/Roster.kt`:

```kotlin
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
```

- [ ] **Step 4: Defer running** — this references the new `BatteryGroup(id, label, targets)` shape created in Task 3. Build/test runs at the end of Task 3.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/model/Roster.kt app/src/test/java/dev/joely/bmsmon/RosterTest.kt
git commit -m "Add Roster model with seed and derived views"
```

---

### Task 2: Roster mutations

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/model/Roster.kt`
- Test: `app/src/test/java/dev/joely/bmsmon/RosterTest.kt`

**Interfaces:**
- Produces: `Roster.addBattery(address, advertisedName): Roster`, `Roster.removeBattery(address): Roster`, `Roster.renameBattery(address, alias): Roster`, `Roster.assignGroup(address, groupId: String?): Roster`, `Roster.addGroup(name): Pair<Roster, String>`, `Roster.renameGroup(groupId, name): Roster`.

- [ ] **Step 1: Write the failing test** — append to `RosterTest.kt`:

```kotlin
    @Test fun addBatteryDedupsByMac() {
        val r = DEFAULT_ROSTER
            .addBattery("AA:BB:CC:DD:EE:FF", "R-12100-NEW")
            .addBattery("aa:bb:cc:dd:ee:ff", "R-12100-NEW")
        assertEquals(9, r.batteries.size)
        val added = r.batteryAt("AA:BB:CC:DD:EE:FF")!!
        assertEquals("R-12100-NEW", added.alias)
        assertNull(added.groupId)
    }

    @Test fun removeRenameAndRegroup() {
        var r = DEFAULT_ROSTER.removeBattery("C8:47:80:15:25:01")
        assertEquals(7, r.batteries.size)
        r = r.renameBattery("C8:47:80:15:07:DE", "Chair spare")
        assertEquals("Chair spare", r.batteryAt("C8:47:80:15:07:DE")!!.alias)
        r = r.assignGroup("C8:47:80:15:07:DE", "2012")
        assertEquals("2012", r.batteryAt("C8:47:80:15:07:DE")!!.groupId)
        r = r.assignGroup("C8:47:80:15:07:DE", null)
        assertNull(r.batteryAt("C8:47:80:15:07:DE")!!.groupId)
    }

    @Test fun addGroupReturnsNewIdAndRename() {
        val (r1, id) = DEFAULT_ROSTER.addGroup("Garage")
        assertEquals(5, r1.groups.size)
        assertEquals("Garage", r1.groups.first { it.id == id }.name)
        val r2 = r1.renameGroup(id, "Shed")
        assertEquals("Shed", r2.groups.first { it.id == id }.name)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RosterTest"`
Expected: FAIL (unresolved `addBattery`, etc.).

- [ ] **Step 3: Write minimal implementation** — append to `Roster.kt`:

```kotlin
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
```

- [ ] **Step 4: Defer running** — builds at end of Task 3 (still depends on the new `BatteryGroup` shape).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/model/Roster.kt app/src/test/java/dev/joely/bmsmon/RosterTest.kt
git commit -m "Add pure roster mutations"
```

---

### Task 3: Refactor BatteryGroup/Fleet to consume the roster

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/model/BatteryGroup.kt`
- Modify: `app/src/main/java/dev/joely/bmsmon/model/Fleet.kt`
- Modify: `app/src/test/java/dev/joely/bmsmon/FleetLogicTest.kt`

**Interfaces:**
- Consumes: `Roster.groupViews()`, `Roster.groupById()` (Task 1).
- Produces: `data class BatteryGroup(id, label, targets: List<BmsTarget>)`; `StageInputs` with trailing field `groups: List<BatteryGroup>`; `StageTarget.addresses(roster: Roster)`; `demoFor(group)` works for any target count. Removes `ALL_GROUPS`, `groupById(id)`, `groupForAddress`, `DEFAULT_GROUP_ID` from these files (now provided by `Roster.kt`).

- [ ] **Step 1: Rewrite `BatteryGroup.kt`** to:

```kotlin
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
```

(Removes `BmsTarget`'s old position only changes structure; `ALL_GROUPS`, `groupById`, `DEFAULT_GROUP_ID` are deleted from here — they now live in `Roster.kt`.)

- [ ] **Step 2: Edit `Fleet.kt`** — three changes:

(a) Replace `StageTarget.addresses()` (lines ~26-29) with a roster-aware version:

```kotlin
fun StageTarget.addresses(roster: Roster): Set<String> = when (this) {
    is StageTarget.Base -> roster.groupById(groupId)?.targets?.map { it.address }?.toSet() ?: emptySet()
    is StageTarget.Single -> setOf(address)
}
```

(b) Add `val groups: List<BatteryGroup>` as the last field of `StageInputs`:

```kotlin
data class StageInputs(
    val fleet: Map<String, BatteryStatus>,
    val dailyDriverId: String,
    val dynamicEnabled: Boolean,
    val manualStage: StageTarget?,
    val manualPinnedAt: Long,
    val lastDischargeAt: Map<String, Long>,
    val holdMs: Long,
    val current: StageTarget,
    val now: Long,
    val groups: List<BatteryGroup>,
)
```

(c) In `resolveStage`, replace both `ALL_GROUPS` usages with `i.groups`:

```kotlin
    fun pick(act: GroupActivity): BatteryGroup? {
        val matches = i.groups.filter { groupActivity(it, i.fleet) == act }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { it.id == i.dailyDriverId } ?: matches.first()
    }

    // 1. discharging right now = the active chair
    pick(GroupActivity.Discharging)?.let { return StageTarget.Base(it.id) }

    // 2. hold: most recent discharge within the window keeps the stage (idle or out of range)
    i.groups
        .mapNotNull { g -> i.lastDischargeAt[g.id]?.let { ts -> g to ts } }
        .filter { i.now - it.second < i.holdMs }
        .maxByOrNull { it.second }
        ?.let { return StageTarget.Base(it.first.id) }
```

(d) Delete the `groupForAddress(address)` function (lines ~99-100) — callers move to `Roster.groupOf` in Task 5.

- [ ] **Step 3: Update `FleetLogicTest.kt`** — replace imports and the `fleetWith`/`inputs` helpers:

Replace `import dev.joely.bmsmon.model.groupById` with:

```kotlin
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupViews
```

Replace the `fleetWith` and `inputs` helpers with:

```kotlin
    private val roster = DEFAULT_ROSTER

    private fun fleetWith(vararg groupStates: Pair<String, BatteryState>): Map<String, BatteryStatus> =
        groupStates.flatMap { (gid, st) ->
            roster.groupById(gid)!!.targets.map { it.address to BatteryStatus(tel(st), reachable = true) }
        }.toMap()

    private fun inputs(
        fleet: Map<String, BatteryStatus>,
        lastDischargeAt: Map<String, Long> = emptyMap(),
        current: StageTarget = StageTarget.Base("2012"),
    ) = StageInputs(fleet, "2012", true, null, 0, lastDischargeAt, hold, current, now, roster.groupViews())
```

- [ ] **Step 4: Run the unit tests** (Tasks 1–3 all compile together now)

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.RosterTest" --tests "dev.joely.bmsmon.FleetLogicTest"`
Expected: PASS — note the *app module still won't fully compile* (ViewModel/screens reference the removed `ALL_GROUPS`); that's fixed in Tasks 5–11. Unit tests compile the `test` + `main` model sources they touch and pass. If the unit-test task fails to compile because `BatteryViewModel.kt` is in the same module, proceed to Task 5 before running the full build; the model/test sources are correct as written.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/model/BatteryGroup.kt app/src/main/java/dev/joely/bmsmon/model/Fleet.kt app/src/test/java/dev/joely/bmsmon/FleetLogicTest.kt
git commit -m "Make BatteryGroup/Fleet derive from the roster"
```

---

### Task 4: Persist the roster in SettingsStore

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`

**Interfaces:**
- Consumes: `Roster`, `Battery`, `Group` (Task 1).
- Produces: `Persisted.roster: Roster?`; `SettingsStore.setRoster(roster: Roster)`.

- [ ] **Step 1: Add the import** at the top of `SettingsStore.kt` (with the other imports):

```kotlin
import dev.joely.bmsmon.model.Battery
import dev.joely.bmsmon.model.Group
import dev.joely.bmsmon.model.Roster
import org.json.JSONArray
```

- [ ] **Step 2: Add the field** to `data class Persisted` (append at the end, before the closing paren):

```kotlin
    val roster: Roster?,
```

- [ ] **Step 3: Add the key** inside `object K`:

```kotlin
        val ROSTER = stringPreferencesKey("roster")
```

- [ ] **Step 4: Populate it in `load()`** — add to the `Persisted(...)` constructor call:

```kotlin
            roster = p[K.ROSTER]?.let(::decodeRoster),
```

- [ ] **Step 5: Add the setter** (next to `setLastTelemetry`):

```kotlin
    suspend fun setRoster(roster: Roster) =
        context.dataStore.edit { it[K.ROSTER] = encodeRoster(roster) }.let {}
```

- [ ] **Step 6: Add encode/decode** at the bottom of the file (next to `encodeTelemetry`):

```kotlin
/** Compact JSON for the editable roster (groups + batteries). */
private fun encodeRoster(r: Roster): String {
    val groups = JSONArray()
    r.groups.forEach { g -> groups.put(JSONObject().put("id", g.id).put("name", g.name)) }
    val batteries = JSONArray()
    r.batteries.forEach { b ->
        batteries.put(JSONObject()
            .put("address", b.address)
            .put("advertisedName", b.advertisedName)
            .put("alias", b.alias)
            .put("groupId", b.groupId ?: JSONObject.NULL))
    }
    return JSONObject().put("groups", groups).put("batteries", batteries).toString()
}

private fun decodeRoster(json: String): Roster? = runCatching {
    val root = JSONObject(json)
    val ga = root.getJSONArray("groups")
    val groups = (0 until ga.length()).map { i ->
        val o = ga.getJSONObject(i)
        Group(o.getString("id"), o.optString("name"))
    }
    val ba = root.getJSONArray("batteries")
    val batteries = (0 until ba.length()).map { i ->
        val o = ba.getJSONObject(i)
        Battery(
            address = o.getString("address"),
            advertisedName = o.optString("advertisedName"),
            alias = o.optString("alias"),
            groupId = if (o.isNull("groupId")) null else o.optString("groupId"),
        )
    }
    Roster(batteries, groups)
}.getOrNull()
```

- [ ] **Step 7: Commit** (compiles after Task 5; no standalone build here)

```bash
git add app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt
git commit -m "Persist the roster to DataStore"
```

---

### Task 5: Wire the roster into the ViewModel

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`
- Modify: `app/src/main/java/dev/joely/bmsmon/ble/BmsRepository.kt`

**Interfaces:**
- Consumes: `Roster`, `DEFAULT_ROSTER`, `DEFAULT_GROUP_ID`, derived views, mutations (Tasks 1-2), `SettingsStore.setRoster`/`Persisted.roster` (Task 4), `StageTarget.addresses(roster)`, `StageInputs(..., groups)` (Task 3).
- Produces (UI consumes in Tasks 7-12): `UiState.roster: Roster`, `UiState.detailAddress: String?`; VM methods `addBattery(address, advertisedName)`, `removeBattery(address)`, `renameBattery(address, alias)`, `setBatteryGroup(address, groupId: String?)`, `createGroupForBattery(address, name)`, `renameGroup(groupId, name)`, `openDetail(address)`, `closeDetail()`; `BmsRepository.setTargets(targets)`.

- [ ] **Step 1: Add `setTargets` to `BmsRepository.kt`** (after `setStage`):

```kotlin
    /** Update the full target set live (roster add/remove). Sampler picks it up next loop. */
    fun setTargets(targets: List<BmsTarget>) {
        allTargets = targets.map { it.copy(address = it.address.trim().uppercase()) }
        val present = allTargets.map { it.address }.toSet()
        stageJobs.keys.filter { it !in present }.forEach { addr -> stageJobs.remove(addr)?.cancel() }
        wakeSampler?.complete(Unit)
    }
```

- [ ] **Step 2: Fix imports in `BatteryViewModel.kt`** — remove:

```kotlin
import dev.joely.bmsmon.model.ALL_GROUPS
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupForAddress
```

and add:

```kotlin
import dev.joely.bmsmon.model.DEFAULT_ROSTER
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.addGroup
import dev.joely.bmsmon.model.addBattery
import dev.joely.bmsmon.model.allTargets
import dev.joely.bmsmon.model.assignGroup
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.groupById
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.model.removeBattery
import dev.joely.bmsmon.model.renameBattery
import dev.joely.bmsmon.model.renameGroup
import dev.joely.bmsmon.model.targetFor
```

(`DEFAULT_GROUP_ID` and `demoFor` imports stay.)

- [ ] **Step 3: Add `Detail` to the `Screen` enum** (top of file):

```kotlin
enum class Screen { Home, Settings, Detail }
```

- [ ] **Step 4: Add roster + detailAddress to `UiState`** — add these fields (place `roster` near `fleet`, `detailAddress` near `screen`):

```kotlin
    val roster: Roster = DEFAULT_ROSTER,
    val detailAddress: String? = null,
```

and change the `demo` default to derive from the seed:

```kotlin
    val demo: List<Telemetry> = demoFor(DEFAULT_ROSTER.groupById(DEFAULT_GROUP_ID)
        ?: BatteryGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_ID, emptyList())),
```

(Add `import dev.joely.bmsmon.model.BatteryGroup` if not already present.)

- [ ] **Step 5: Update `UiState`'s derived members** to read `roster`:

```kotlin
    val dailyDriver: BatteryGroup
        get() = roster.groupById(dailyDriverId) ?: BatteryGroup(dailyDriverId, dailyDriverId, emptyList())

    fun stageItems(): List<StageItem> {
        if (!monitoring) return demo.map { StageItem(it, false) }
        val targets = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.targets ?: emptyList()
            is StageTarget.Single -> roster.targetFor(t.address)?.let { listOf(it) } ?: emptyList()
        }
        return targets.map { tg ->
            val tel = fleet[tg.address]?.telemetry?.copy(name = tg.name)
                ?: Telemetry(tg.name, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            StageItem(tel, tg.address in regenAddrs)
        }
    }

    val stageRegen: Boolean
        get() = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.targets?.any { it.address in regenAddrs } ?: false
            is StageTarget.Single -> t.address in regenAddrs
        }

    val stageLabel: String
        get() = when (val t = stageTarget) {
            is StageTarget.Base -> roster.groupById(t.groupId)?.label ?: t.groupId
            is StageTarget.Single -> roster.batteryAt(t.address)?.alias ?: t.address
        }

    val stageActivity: GroupActivity
        get() = (stageTarget as? StageTarget.Base)?.let { roster.groupById(it.groupId)?.let { g -> groupActivity(g, fleet) } }
            ?: GroupActivity.Unknown
```

And in `stageAlert()`, change `stageTarget.addresses()` to `stageTarget.addresses(roster)`.

- [ ] **Step 6: Update the `init` load block** — load the roster first, then resolve daily driver against it:

In the `_state.update { s -> ... }` inside `init`, set `val roster = p.roster ?: DEFAULT_ROSTER` at the top of the lambda, change `val dd = groupById(p.dailyDriverId ?: s.dailyDriverId)` to:

```kotlin
                val roster = p.roster ?: DEFAULT_ROSTER
                val dd = roster.groupById(p.dailyDriverId ?: s.dailyDriverId)
                    ?: roster.groupViews().firstOrNull()
                    ?: BatteryGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_ID, emptyList())
```

and add `roster = roster,` to the `s.copy(...)`.

- [ ] **Step 7: Update the remaining `groupById`/`ALL_GROUPS`/`groupForAddress` call sites** in the ViewModel body:

- `setDailyDriver`: `val g = groupById(id)` → `val g = _state.value.roster.groupById(id) ?: return`
- `startMonitoring`: `targets = ALL_GROUPS.flatMap { it.targets }` → `targets = _state.value.roster.allTargets()`
- `onTelemetry`: `val group = groupForAddress(addr)` → `val group = _state.value.roster.groupOf(addr)`
- `refresh`: `ALL_GROUPS.forEach { g -> ... }` → `st.roster.groupViews().forEach { g -> ... }`; and add `groups = st.roster.groupViews()` as the trailing arg of the `StageInputs(...)` call.
- `currentStageAddrs`: `_state.value.stageTarget.addresses()` → `_state.value.stageTarget.addresses(_state.value.roster)`

- [ ] **Step 8: Add roster-mutation methods + detail nav** (place after `setDailyDriver`):

```kotlin
    // --- roster editing ---
    private fun updateRoster(transform: (Roster) -> Roster) {
        _state.update { it.copy(roster = transform(it.roster)) }
        val r = _state.value.roster
        viewModelScope.launch { store.setRoster(r) }
        if (_state.value.monitoring) repository.setTargets(r.allTargets())
        refresh()
    }

    fun addBattery(address: String, advertisedName: String) =
        updateRoster { it.addBattery(address, advertisedName) }

    fun removeBattery(address: String) {
        val a = address.uppercase()
        _state.update { it.copy(disabled = it.disabled - a, fleet = it.fleet - a) }
        repository.setDisabled(_state.value.disabled)
        updateRoster { it.removeBattery(a) }
    }

    fun renameBattery(address: String, alias: String) =
        updateRoster { it.renameBattery(address, alias) }

    fun setBatteryGroup(address: String, groupId: String?) =
        updateRoster { it.assignGroup(address, groupId) }

    fun createGroupForBattery(address: String, name: String) =
        updateRoster {
            val (r, id) = it.addGroup(name)
            r.assignGroup(address, id)
        }

    fun renameGroup(groupId: String, name: String) =
        updateRoster { it.renameGroup(groupId, name) }

    fun openDetail(address: String) = _state.update { it.copy(screen = Screen.Detail, detailAddress = address) }
    fun closeDetail() = _state.update { it.copy(screen = Screen.Home, detailAddress = null) }
```

- [ ] **Step 9: Build the app module**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: still FAILS in `AllBatteriesScreen.kt`, `SettingsScreen.kt`, `StageScreen.kt` (they reference removed `ALL_GROUPS`) — those are Tasks 8/12 and a small SettingsScreen fix below. To unblock the build now, also do Step 10.

- [ ] **Step 10: Fix the two simple screen references** so the module compiles:

In `ui/settings/SettingsScreen.kt` (line ~43 import + ~268 usage): replace `import dev.joely.bmsmon.model.ALL_GROUPS` with `import dev.joely.bmsmon.model.groupViews`, and change `ALL_GROUPS.forEach { g ->` to `state.roster.groupViews().forEach { g ->`. (Confirm `state` is in scope there; it is — `SettingsScreen(state, ...)`.)

`AllBatteriesScreen.kt` and `StageScreen.kt` are rewritten in Tasks 8 and 12; leave them until then. If building now, temporarily change `AllBatteriesScreen.kt` line 85 `ALL_GROUPS.flatMap` → `state.roster.groupViews().flatMap` and line 124 `ALL_GROUPS.forEach` → `state.roster.groupViews().forEach` and line 47 import → `groupViews`, so the module compiles end-to-end.

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt app/src/main/java/dev/joely/bmsmon/ble/BmsRepository.kt app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt app/src/main/java/dev/joely/bmsmon/ui/all/AllBatteriesScreen.kt
git commit -m "Wire the editable roster into the ViewModel"
```

---

### Task 6: BLE scanner (compatible-only)

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/ble/BleScanner.kt`
- Test: `app/src/test/java/dev/joely/bmsmon/BleScannerTest.kt`

**Interfaces:**
- Produces: `fun isCompatibleBmsName(name: String?): Boolean`; `data class DiscoveredDevice(address, name)`; `class BleScanner(context)` with `start(onFound: (DiscoveredDevice) -> Unit)` and `stop()`.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/dev/joely/bmsmon/BleScannerTest.kt`:

```kotlin
package dev.joely.bmsmon

import dev.joely.bmsmon.ble.isCompatibleBmsName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScannerTest {
    @Test fun acceptsKnownRedodoAndFamilyPrefixes() {
        assertTrue(isCompatibleBmsName("R-12100BNNA70-A02402"))
        assertTrue(isCompatibleBmsName("RO-24100"))
        assertTrue(isCompatibleBmsName("L-51100"))
        assertTrue(isCompatibleBmsName("LT-12100"))
        assertTrue(isCompatibleBmsName("PQ-12100"))
        assertTrue(isCompatibleBmsName("SS-12100"))
        assertTrue(isCompatibleBmsName("S-12100"))
    }

    @Test fun rejectsUnknownOrNull() {
        assertFalse(isCompatibleBmsName(null))
        assertFalse(isCompatibleBmsName(""))
        assertFalse(isCompatibleBmsName("MyHeadphones"))
        assertFalse(isCompatibleBmsName("X-12100"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BleScannerTest"`
Expected: FAIL (unresolved `isCompatibleBmsName`).

- [ ] **Step 3: Write `BleScanner.kt`**:

```kotlin
package dev.joely.bmsmon.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/** BLE-advertised-name prefixes for compatible BMS modules (see bmsmon/CLAUDE.md). */
private val KNOWN_PREFIXES = listOf(
    "R-12", "R-24", "RO-12", "RO-24",
    "L-12", "L-24", "L-51", "LT-",
    "P-12", "P-24", "PQ-12", "PQ-24",
    "SS-", "S-",
)

/** True only for advertised names that match a known compatible BMS prefix. */
fun isCompatibleBmsName(name: String?): Boolean {
    val n = name?.trim().orEmpty()
    if (n.isEmpty()) return false
    return KNOWN_PREFIXES.any { n.startsWith(it, ignoreCase = true) }
}

/** A compatible device seen during a scan. */
data class DiscoveredDevice(val address: String, val name: String)

/**
 * Scans for compatible BMS devices only. SAFETY: surfaces nothing but [KNOWN_PREFIXES] matches,
 * and never connects here — discovery only.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    fun start(onFound: (DiscoveredDevice) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        val s = adapter.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (isCompatibleBmsName(name)) {
                    onFound(DiscoveredDevice(result.device.address.uppercase(), name!!.trim()))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.d("BleScanner", "scan failed: $errorCode")
            }
        }
        scanner = s
        callback = cb
        s.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
    }

    fun stop() {
        runCatching { callback?.let { scanner?.stopScan(it) } }
        callback = null
        scanner = null
    }
}
```

- [ ] **Step 4: Run to verify the unit test passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.BleScannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ble/BleScanner.kt app/src/test/java/dev/joely/bmsmon/BleScannerTest.kt
git commit -m "Add compatible-only BLE scanner"
```

---

### Task 7: Scan sheet UI

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/ui/scan/ScanSheet.kt`

**Interfaces:**
- Consumes: `BleScanner`, `DiscoveredDevice` (Task 6); `Roster.batteryAt` (Task 1); `hasBlePermissions`/`blePermissions` (existing).
- Produces: `@Composable fun ScanSheet(roster: Roster, onAdd: (address: String, name: String) -> Unit, onDismiss: () -> Unit)`.

- [ ] **Step 1: Write `ScanSheet.kt`**:

```kotlin
package dev.joely.bmsmon.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.ble.BleScanner
import dev.joely.bmsmon.ble.DiscoveredDevice
import dev.joely.bmsmon.ble.hasBlePermissions
import dev.joely.bmsmon.model.Roster
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
    roster: Roster,
    onAdd: (address: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bm.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val found = remember { mutableStateMapOf<String, DiscoveredDevice>() }
    val hasPerms = hasBlePermissions(context)

    DisposableEffect(Unit) {
        val scanner = BleScanner(context)
        if (hasPerms) scanner.start { dev -> found[dev.address] = dev }
        onDispose { scanner.stop() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Text("Add a battery", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (!hasPerms) "Bluetooth permission needed — enable it, then reopen."
                else "Scanning for nearby batteries…",
                color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            val devices = found.values.sortedBy { it.name }
            if (devices.isEmpty()) {
                Text("No batteries found yet.", color = c.text3, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(devices, key = { it.address }) { dev ->
                    val existing = roster.batteryAt(dev.address)
                    ScanRow(dev, existingAlias = existing?.alias, onAdd = { onAdd(dev.address, dev.name) })
                }
            }
        }
    }
}

@Composable
private fun ScanRow(dev: DiscoveredDevice, existingAlias: String?, onAdd: () -> Unit) {
    val c = Bm.colors
    val known = existingAlias != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(c.card2)
            .then(if (known) Modifier else Modifier.clickable(onClick = onAdd))
            .alpha(if (known) 0.45f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(dev.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(dev.address, color = c.text3, fontFamily = MonoFont, fontSize = 11.sp)
            }
            Text(
                if (known) "Added as $existingAlias" else "+ Add",
                color = if (known) c.text3 else Bm.accent,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ui/scan/ScanSheet.kt
git commit -m "Add scan sheet for discovering batteries"
```

---

### Task 8: All Batteries — header "+", long-press actions, swipe-delete, single-tap → detail

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/all/AllBatteriesScreen.kt`
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: VM methods `addBattery`, `removeBattery`, `renameBattery`, `setBatteryGroup`, `createGroupForBattery`, `renameGroup`, `openDetail`, `pinStage` (Task 5); `ScanSheet` (Task 7); `state.roster` for the group list.
- Produces: `AllBatteriesScreen` gains params `onOpenDetail`, `onAddScan`, `onRemove`, `onRename`, `onSetGroup`, `onCreateGroup`, `onRenameGroup`, plus the `+` header button and per-row swipe/menu/dialogs. The `Row.group` becomes nullable to support ungrouped batteries.

- [ ] **Step 1: Rewrite `AllBatteriesScreen.kt`.** Key changes from the current file:
  - Build rows from `state.roster`: grouped batteries via `state.roster.groupViews()`, **plus** ungrouped batteries (`state.roster.batteries.filter { it.groupId == null }`) as rows with `group = null`.
  - Add a `+` icon button in the header row.
  - Add per-row state for the action menu and three dialogs (rename battery, group picker, rename group), and confirmation for delete.
  - Wrap each row in `SwipeToDismissBox` (EndToStart only) revealing a trash icon; on dismiss, show the delete confirmation (snap back if cancelled).
  - Replace `onDoubleClick = onPin` with `onClick = onOpenDetail`; keep `onLongClick = { menuOpen = true }`.

Full file:

```kotlin
package dev.joely.bmsmon.ui.all

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.FilterKey
import dev.joely.bmsmon.SortKey
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.BatteryState
import dev.joely.bmsmon.model.BatteryStatus
import dev.joely.bmsmon.model.BmsTarget
import dev.joely.bmsmon.model.Telemetry
import dev.joely.bmsmon.model.groupViews
import dev.joely.bmsmon.ui.ChargingBolt
import dev.joely.bmsmon.ui.rememberBoltAlpha
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import dev.joely.bmsmon.ui.theme.socSeverity
import kotlin.math.roundToInt

/** A row's group context: id+label, or null when the battery is ungrouped. */
private data class RowGroup(val id: String, val label: String)

private data class Row(val group: RowGroup?, val target: BmsTarget, val status: BatteryStatus?) {
    val tele: Telemetry? get() = status?.telemetry
    val reachable: Boolean get() = status?.reachable == true
}

private fun activityRank(t: Telemetry?): Int = when (t?.state) {
    BatteryState.Discharging -> 0
    BatteryState.Charging -> 1
    BatteryState.Idle -> 2
    else -> 3
}

@Composable
fun AllBatteriesScreen(
    state: UiState,
    onSetSort: (SortKey) -> Unit,
    onToggleFilter: (FilterKey) -> Unit,
    onSetFilterBase: (String) -> Unit,
    onPinBase: (String) -> Unit,
    onPinSingle: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onAddScan: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onSetGroup: (String, String?) -> Unit,
    onCreateGroup: (String, String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Bm.colors
    val groupViews = state.roster.groupViews()
    val grouped = groupViews.flatMap { g ->
        g.targets.map { t -> Row(RowGroup(g.id, g.label), t, state.fleet[t.address]) }
    }
    val ungrouped = state.roster.batteries.filter { it.groupId == null }
        .map { b -> Row(null, BmsTarget(b.address, b.alias), state.fleet[b.address]) }
    var rows = grouped + ungrouped

    if (FilterKey.ReachableOnly in state.filters) rows = rows.filter { it.reachable }
    if (FilterKey.ActiveOnly in state.filters) rows = rows.filter { activityRank(it.tele) <= 1 }
    if (FilterKey.ByBase in state.filters) rows = rows.filter { it.group?.id == state.filterBaseId }
    if (FilterKey.DailyDriverOnly in state.filters) rows = rows.filter { it.group?.id == state.dailyDriverId }

    rows = when (state.sortKey) {
        SortKey.Activity -> rows.sortedWith(compareBy({ activityRank(it.tele) }, { -(it.tele?.soc ?: -1f) }))
        SortKey.Soc -> rows.sortedByDescending { it.tele?.soc ?: -1f }
        SortKey.Base -> rows
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("All Batteries", color = c.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.monitoring) {
                    Text("Disconnect all", color = Bm.power, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onDisconnectAll).padding(4.dp))
                }
                Box(
                    Modifier.padding(start = 6.dp).size(34.dp).clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onAddScan),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, "Add battery", Modifier.size(22.dp), tint = Bm.accent)
                }
            }
        }

        ChipRow("Sort") {
            Chip("Activity", state.sortKey == SortKey.Activity) { onSetSort(SortKey.Activity) }
            Chip("SOC", state.sortKey == SortKey.Soc) { onSetSort(SortKey.Soc) }
            Chip("Base", state.sortKey == SortKey.Base) { onSetSort(SortKey.Base) }
        }
        ChipRow("Filter") {
            Chip("Reachable", FilterKey.ReachableOnly in state.filters) { onToggleFilter(FilterKey.ReachableOnly) }
            Chip("Active", FilterKey.ActiveOnly in state.filters) { onToggleFilter(FilterKey.ActiveOnly) }
            Chip("Daily driver", FilterKey.DailyDriverOnly in state.filters) { onToggleFilter(FilterKey.DailyDriverOnly) }
            Chip("By base", FilterKey.ByBase in state.filters) { onToggleFilter(FilterKey.ByBase) }
        }
        if (FilterKey.ByBase in state.filters) {
            ChipRow("Base") {
                groupViews.forEach { g -> Chip(g.label, state.filterBaseId == g.id) { onSetFilterBase(g.id) } }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(rows, key = { it.target.address }) { row ->
                SwipeableBatteryRow(
                    row = row,
                    groups = groupViews.map { RowGroup(it.id, it.label) },
                    isStage = row.group?.id == state.stageGroupId && state.monitoring,
                    isDailyDriver = row.group?.id == state.dailyDriverId,
                    disabled = row.target.address in state.disabled,
                    monitoring = state.monitoring,
                    onOpenDetail = { onOpenDetail(row.target.address) },
                    onPin = { if (row.group != null) onPinBase(row.group.id) else onPinSingle(row.target.address) },
                    onDisconnect = { onDisconnect(row.target.address) },
                    onReconnect = { onReconnect(row.target.address) },
                    onRemove = { onRemove(row.target.address) },
                    onRename = { onRename(row.target.address, it) },
                    onSetGroup = { onSetGroup(row.target.address, it) },
                    onCreateGroup = { onCreateGroup(row.target.address, it) },
                    onRenameGroup = { row.group?.let { g -> onRenameGroup(g.id, it) } },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    val c = Bm.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bm.colors
    val border = if (selected) Bm.accent else c.border
    val bg = if (selected) Bm.accent.copy(alpha = 0.14f) else Color.Transparent
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Bm.accent else c.text2, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableBatteryRow(
    row: Row,
    groups: List<RowGroup>,
    isStage: Boolean,
    isDailyDriver: Boolean,
    disabled: Boolean,
    monitoring: Boolean,
    onOpenDetail: () -> Unit,
    onPin: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
    onRename: (String) -> Unit,
    onSetGroup: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (String) -> Unit,
) {
    val c = Bm.colors
    var confirmDelete by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) { confirmDelete = true; false } else false
        },
    )
    // Always snap back; deletion happens through the dialog.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismissState.reset()
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp))
                    .background(Bm.power.copy(alpha = 0.18f)).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, "Remove", Modifier.size(22.dp), tint = Bm.power)
            }
        },
    ) {
        BatteryRow(
            row = row, groups = groups, isStage = isStage, isDailyDriver = isDailyDriver,
            disabled = disabled, monitoring = monitoring,
            onOpenDetail = onOpenDetail, onPin = onPin,
            onDisconnect = onDisconnect, onReconnect = onReconnect,
            onRemoveRequest = { confirmDelete = true },
            onRename = onRename, onSetGroup = onSetGroup,
            onCreateGroup = onCreateGroup, onRenameGroup = onRenameGroup,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.card,
            title = { Text("Remove battery?", color = c.text) },
            text = { Text("Remove “${row.target.name}” from the roster? Are you sure?", color = c.text2) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onRemove() }) { Text("Remove", color = Bm.power) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.text2) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatteryRow(
    row: Row,
    groups: List<RowGroup>,
    isStage: Boolean,
    isDailyDriver: Boolean,
    disabled: Boolean,
    monitoring: Boolean,
    onOpenDetail: () -> Unit,
    onPin: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onRemoveRequest: () -> Unit,
    onRename: (String) -> Unit,
    onSetGroup: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (String) -> Unit,
) {
    val c = Bm.colors
    val t = row.tele
    val reachable = monitoring && row.reachable && !disabled
    val dim = disabled || (monitoring && !row.reachable)
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var groupPickOpen by remember { mutableStateOf(false) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var renameGroupOpen by remember { mutableStateOf(false) }

    val (stateLabel, stateColor) = when {
        disabled -> "Disconnected" to c.text3
        monitoring && !row.reachable -> "Out of range" to c.text3
        t?.state == BatteryState.Discharging -> "Discharging" to Bm.power
        t?.state == BatteryState.Charging -> "Charging" to Bm.accent
        t?.state == BatteryState.Idle -> "Idle" to c.text2
        t == null && monitoring -> "Connecting…" to c.text3
        else -> "—" to c.text3
    }
    val borderColor = if (isStage) Bm.accent else c.border
    val socColor = if (t != null) socSeverity(t.soc, Bm.accent) else c.text3

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (isStage) Bm.accent.copy(alpha = 0.08f) else c.card2)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .combinedClickable(onClick = onOpenDetail, onLongClick = { menuOpen = true })
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        RowActionMenu(
            expanded = menuOpen,
            inGroup = row.group != null,
            onDismiss = { menuOpen = false },
            onPin = onPin,
            onRename = { renameOpen = true },
            onChangeGroup = { groupPickOpen = true },
            onRenameGroup = { renameGroupOpen = true },
            onRemove = onRemoveRequest,
        )
        // Header line: identity + state on the left, SOC% (+ link button) on the right.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).alpha(if (dim) 0.5f else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.target.name, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (isDailyDriver) {
                    Icon(Icons.Filled.Star, "Daily driver", Modifier.padding(start = 6.dp).size(12.dp), tint = Bm.accent)
                }
                if (isStage) {
                    Text("STAGE", color = Bm.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stateLabel, color = stateColor, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                if (t != null) {
                    Text(" · %.1f V".format(t.voltage), color = c.text3, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
            if (reachable && t?.state == BatteryState.Charging) {
                Icon(ChargingBolt, "Charging",
                    Modifier.padding(start = 8.dp).size(width = 11.dp, height = 16.dp),
                    tint = Bm.accent.copy(alpha = rememberBoltAlpha(0.35f, 1f)))
            }
            Text(if (t != null) "${t.soc.roundToInt()}%" else "—",
                color = socColor, fontFamily = MonoFont, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alpha(if (dim) 0.5f else 1f).padding(start = 8.dp))
            if (monitoring) {
                Box(
                    Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = if (disabled) onReconnect else onDisconnect),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (disabled) Icons.Filled.Link else Icons.Filled.LinkOff,
                        if (disabled) "Reconnect" else "Disconnect",
                        Modifier.size(17.dp), tint = if (disabled) Bm.accent else c.text3,
                    )
                }
            }
        }
        if (t != null) {
            val fullAh = if (t.fullChargeAh > 0f) t.fullChargeAh else 100f
            val capPct = (t.capacityAh / fullAh).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth().alpha(if (dim) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                Text("CAPACITY", color = c.text3, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(c.inputBg)) {
                    Box(Modifier.fillMaxWidth(capPct).height(5.dp).clip(RoundedCornerShape(3.dp)).background(socColor))
                }
                Text("${t.capacityAh.roundToInt()} Ah", color = c.text2, fontFamily = MonoFont, fontSize = 11.sp,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }

    if (renameOpen) {
        TextPromptDialog("Rename battery", row.target.name, "Name",
            onConfirm = { renameOpen = false; onRename(it) }, onDismiss = { renameOpen = false })
    }
    if (newGroupOpen) {
        TextPromptDialog("New group", "", "Group name",
            onConfirm = { newGroupOpen = false; onCreateGroup(it) }, onDismiss = { newGroupOpen = false })
    }
    if (renameGroupOpen && row.group != null) {
        TextPromptDialog("Rename group", row.group.label, "Group name",
            onConfirm = { renameGroupOpen = false; onRenameGroup(it) }, onDismiss = { renameGroupOpen = false })
    }
    if (groupPickOpen) {
        GroupPickerDialog(
            groups = groups,
            currentGroupId = row.group?.id,
            onPick = { groupPickOpen = false; onSetGroup(it) },
            onNewGroup = { groupPickOpen = false; newGroupOpen = true },
            onDismiss = { groupPickOpen = false },
        )
    }
}

@Composable
private fun RowActionMenu(
    expanded: Boolean,
    inGroup: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onChangeGroup: () -> Unit,
    onRenameGroup: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Bm.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.background(c.card)) {
        DropdownMenuItem(
            text = { Text("Pin to Main Stage", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(18.dp), tint = Bm.accent) },
            onClick = { onDismiss(); onPin() },
        )
        DropdownMenuItem(
            text = { Text("Rename battery", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, Modifier.size(18.dp), tint = c.icon) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(if (inGroup) "Change group" else "Add to group", color = c.text, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = c.icon) },
            onClick = { onDismiss(); onChangeGroup() },
        )
        if (inGroup) {
            DropdownMenuItem(
                text = { Text("Rename group", color = c.text, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null, Modifier.size(18.dp), tint = c.icon) },
                onClick = { onDismiss(); onRenameGroup() },
            )
        }
        DropdownMenuItem(
            text = { Text("Remove battery", color = Bm.power, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = Bm.power) },
            onClick = { onDismiss(); onRemove() },
        )
    }
}

@Composable
private fun GroupPickerDialog(
    groups: List<RowGroup>,
    currentGroupId: String?,
    onPick: (String?) -> Unit,
    onNewGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bm.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Move to group", color = c.text) },
        text = {
            Column {
                DropdownRow("Ungrouped", currentGroupId == null) { onPick(null) }
                groups.forEach { g -> DropdownRow(g.label, currentGroupId == g.id) { onPick(g.id) } }
                DropdownRow("+ New group…", selected = false, accent = true) { onNewGroup() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.text2) } },
    )
}

@Composable
private fun DropdownRow(label: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val c = Bm.colors
    Text(
        label,
        color = when { accent -> Bm.accent; selected -> Bm.accent; else -> c.text },
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bm.colors
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(title, color = c.text) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label, color = c.text3) }, singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text("Save", color = Bm.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}
```

- [ ] **Step 2: Thread the new params + scan sheet through `HomeScreen.kt`.** Add a scan-sheet trigger and the new callbacks. In `HomeScreen`:
  - Add params: `onAddScan: () -> Unit, onOpenDetail: (String) -> Unit, onRemove: (String) -> Unit, onRename: (String, String) -> Unit, onSetGroup: (String, String?) -> Unit, onCreateGroup: (String, String) -> Unit, onRenameGroup: (String, String) -> Unit, onPinSingle: (String) -> Unit`.
  - In the `AllBatteriesScreen(...)` call, pass: `onPinSingle = { addr -> onPinSingle(addr); scope.launch { pager.animateScrollToPage(0) } }`, `onAddScan = onAddScan`, `onOpenDetail = onOpenDetail`, `onRemove = onRemove`, `onRename = onRename`, `onSetGroup = onSetGroup`, `onCreateGroup = onCreateGroup`, `onRenameGroup = onRenameGroup`.

(The scan sheet itself is hosted in `App.kt` in Task 11 via a `showScan` state so it overlays the whole app; `onAddScan` just flips that flag.)

- [ ] **Step 3: Build** (App.kt wiring lands in Task 11; to compile now, temporarily pass `onAddScan = {}`, `onOpenDetail = {}`, etc. from `App.kt` — Task 11 replaces them).

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: Compiles once `App.kt` passes the new `HomeScreen` params (do the minimal `App.kt` wiring from Task 11 Step 1 first if needed).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ui/all/AllBatteriesScreen.kt app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt
git commit -m "All Batteries: add button, long-press actions, swipe-to-delete, tap-to-detail"
```

---

### Task 9: Battery detail screen

**Files:**
- Create: `app/src/main/java/dev/joely/bmsmon/ui/detail/BatteryDetailScreen.kt`

**Interfaces:**
- Consumes: `UiState` (`roster`, `fleet`, `detailAddress`, `tempFahrenheit`); `Roster.batteryAt`, `Roster.groupOf`; `Telemetry` fields incl. `cells`, `protections`, `mosfetTemp`, `soh`, `cycles`, `fullChargeAh`.
- Produces: `@Composable fun BatteryDetailScreen(state: UiState, onBack: () -> Unit)`.

- [ ] **Step 1: Write `BatteryDetailScreen.kt`**:

```kotlin
package dev.joely.bmsmon.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.joely.bmsmon.UiState
import dev.joely.bmsmon.model.batteryAt
import dev.joely.bmsmon.model.groupOf
import dev.joely.bmsmon.ui.theme.Bm
import dev.joely.bmsmon.ui.theme.MonoFont
import kotlin.math.roundToInt

@Composable
fun BatteryDetailScreen(state: UiState, onBack: () -> Unit) {
    val c = Bm.colors
    val address = state.detailAddress
    val battery = address?.let { state.roster.batteryAt(it) }
    val tele = address?.let { state.fleet[it]?.telemetry }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(22.dp), tint = c.icon)
            }
            Text(battery?.alias ?: "Battery", color = c.text, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }

        if (battery == null) {
            Text("Battery not found.", color = c.text3, fontSize = 14.sp, modifier = Modifier.padding(18.dp))
            return@Column
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Section("Identity") {
                KeyVal("Alias", battery.alias)
                KeyVal("Real name", battery.advertisedName)
                KeyVal("MAC", battery.address, mono = true)
                KeyVal("Group", state.roster.groupOf(battery.address)?.label ?: "Ungrouped")
            }

            if (tele == null) {
                Section("Telemetry") {
                    Text("No live or stored reading yet. Start monitoring to populate.",
                        color = Bm.colors.text3, fontSize = 13.sp)
                }
            } else {
                val tempC = tele.temp
                val temp = if (state.tempFahrenheit) tempC * 9f / 5f + 32f else tempC
                val tUnit = if (state.tempFahrenheit) "°F" else "°C"
                val mosC = tele.mosfetTemp.toFloat()
                val mos = if (state.tempFahrenheit) mosC * 9f / 5f + 32f else mosC

                Section("State of charge") {
                    KeyVal("SOC", "${tele.soc.roundToInt()} %", mono = true)
                    KeyVal("SOH", "${tele.soh} %", mono = true)
                    KeyVal("Cycles", tele.cycles.toString(), mono = true)
                    KeyVal("Capacity", "%.1f / %.1f Ah".format(tele.capacityAh, tele.fullChargeAh), mono = true)
                }
                Section("Power") {
                    KeyVal("Voltage", "%.2f V".format(tele.voltage), mono = true)
                    KeyVal("Current", "%.2f A".format(tele.current), mono = true)
                    KeyVal("Power", "%.1f W".format(tele.powerW), mono = true)
                    KeyVal("State", tele.state.name, mono = false)
                }
                Section("Temperature") {
                    KeyVal("Cell temp", "%.1f %s".format(temp, tUnit), mono = true)
                    KeyVal("MOSFET temp", "%.1f %s".format(mos, tUnit), mono = true)
                }
                Section("Cells (${tele.cells.size})") {
                    if (tele.cells.isEmpty()) {
                        Text("No per-cell data in the last reading.", color = c.text3, fontSize = 13.sp)
                    } else {
                        val mn = tele.cells.min(); val mx = tele.cells.max()
                        KeyVal("Min / Max", "%.3f / %.3f V".format(mn, mx), mono = true)
                        KeyVal("Delta", "%.0f mV".format((mx - mn) * 1000f), mono = true)
                        tele.cells.forEachIndexed { i, v ->
                            val color = when (v) { mx -> Bm.accent; mn -> Bm.power; else -> c.text }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cell ${i + 1}", color = c.text2, fontSize = 13.sp)
                                Text("%.3f V".format(v), color = color, fontFamily = MonoFont, fontSize = 13.sp)
                            }
                        }
                    }
                }
                if (tele.protections.isNotEmpty()) {
                    Section("Active protections") {
                        tele.protections.forEach { p -> Text("• $p", color = Bm.power, fontSize = 13.sp) }
                    }
                }
            }

            Section("History") {
                Text("Graphs from logged data — coming soon.", color = Bm.colors.text3, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Bm.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(c.card2)
            .border(1.dp, c.border, RoundedCornerShape(11.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        content()
    }
}

@Composable
private fun KeyVal(key: String, value: String, mono: Boolean = false) {
    val c = Bm.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = c.text2, fontSize = 13.sp)
        Text(value, color = c.text, fontSize = 13.sp,
            fontFamily = if (mono) MonoFont else null, fontWeight = FontWeight.SemiBold)
    }
}
```

- [ ] **Step 2: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (screen not yet routed; that's Task 11).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ui/detail/BatteryDetailScreen.kt
git commit -m "Add battery detail screen"
```

---

### Task 10: Empty-roster "+" and 3+ pack stage scroll

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/home/StageScreen.kt`

**Interfaces:**
- Consumes: `StageItem` list (existing); a new `onAddScan` callback and `isEmpty` flag.
- Produces: `StageScreen(items, tempInF, isEmpty, onAddScan, modifier)`.

- [ ] **Step 1: Update `StageScreen` signature + body** to show a big `+` when the roster is empty, and to scroll horizontally for 3+ packs:

Replace the `StageScreen` composable (top of file) with:

```kotlin
@Composable
fun StageScreen(
    items: List<StageItem>,
    tempInF: Boolean,
    isEmpty: Boolean,
    onAddScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Bm.colors
    if (isEmpty) {
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(Bm.accent.copy(alpha = 0.12f))
                    .border(2.dp, Bm.accent, CircleShape).clickable(onClick = onAddScan),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, "Add a battery", Modifier.size(64.dp), tint = Bm.accent)
            }
            Text("Add a battery", color = c.text2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp))
        }
        return
    }
    if (items.size > 2) {
        Row(modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                BatteryBlock(item, tempInF, Modifier.width(320.dp))
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            items.forEach { item -> BatteryBlock(item, tempInF, Modifier.weight(1f)) }
        }
    }
}
```

Add these imports to `StageScreen.kt`:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
```

(Note: `BatteryBlock` currently takes a `Modifier` and uses `fillMaxWidth()` internally; passing `Modifier.width(320.dp)` is fine — it constrains width while the internal `fillMaxWidth` fills that 320dp.)

- [ ] **Step 2: Update the call in `HomeScreen.kt`** (page 0):

```kotlin
                    0 -> StageScreen(
                        items = state.stageItems(),
                        tempInF = state.tempFahrenheit,
                        isEmpty = state.roster.batteries.isEmpty(),
                        onAddScan = onAddScan,
                    )
```

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ui/home/StageScreen.kt app/src/main/java/dev/joely/bmsmon/ui/home/HomeScreen.kt
git commit -m "Stage: empty-roster add button and horizontal scroll for 3+ packs"
```

---

### Task 11: Route detail screen + host scan sheet in App.kt

**Files:**
- Modify: `app/src/main/java/dev/joely/bmsmon/ui/App.kt`

**Interfaces:**
- Consumes: `Screen.Detail` (Task 5), `BatteryDetailScreen` (Task 9), `ScanSheet` (Task 7), VM methods (Task 5), `HomeScreen` new params (Tasks 8/10).
- Produces: full navigation + scan-sheet hosting; permission prompt before the scan opens.

- [ ] **Step 1: Add imports** to `App.kt`:

```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.joely.bmsmon.ui.detail.BatteryDetailScreen
import dev.joely.bmsmon.ui.scan.ScanSheet
```

- [ ] **Step 2: Add scan-sheet state + permission-aware opener** inside `App`, after `onMonitorToggle`:

```kotlin
    var showScan by remember { mutableStateOf(false) }
    val scanPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.all { it }) showScan = true }
    val onAddScan: () -> Unit = {
        if (hasBlePermissions(context)) showScan = true else scanPermLauncher.launch(blePermissions())
    }
```

- [ ] **Step 3: Extend the `when (state.screen)`** block — add the `Detail` branch and pass the new `HomeScreen` params. Replace the `Screen.Home -> HomeScreen(...)` call's argument list to include:

```kotlin
                        onAddScan = onAddScan,
                        onOpenDetail = vm::openDetail,
                        onRemove = vm::removeBattery,
                        onRename = vm::renameBattery,
                        onSetGroup = vm::setBatteryGroup,
                        onCreateGroup = vm::createGroupForBattery,
                        onRenameGroup = vm::renameGroup,
                        onPinSingle = { addr -> vm.pinStage(dev.joely.bmsmon.model.StageTarget.Single(addr)) },
```

and add after the `Screen.Settings -> ...` branch:

```kotlin
                    Screen.Detail -> BatteryDetailScreen(state = state, onBack = vm::closeDetail)
```

- [ ] **Step 4: Host the scan sheet** — just before the final closing braces of the outer `Box` (inside `BmTheme { Box { ... } }`), add:

```kotlin
            if (showScan) {
                ScanSheet(
                    roster = state.roster,
                    onAdd = { address, name -> vm.addBattery(address, name) },
                    onDismiss = { showScan = false },
                )
            }
```

- [ ] **Step 5: Build + run unit tests**

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests PASS (`RosterTest`, `BleScannerTest`, `FleetLogicTest`, `BmsProtocolTest`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/joely/bmsmon/ui/App.kt
git commit -m "Route detail screen and host the scan sheet"
```

---

### Task 12: Manual verification on device, then restore the seed

**Files:** none (verification only).

**Interfaces:** Consumes the whole feature.

- [ ] **Step 1: Install and launch**

Run: `cd android && ./gradlew :app:installDebug` then launch the app on the connected phone (wireless ADB per `android/README.md`).

- [ ] **Step 2: Verify the seed is intact** — On first launch after install, confirm All Batteries still shows the 4 bases (`2012`/`2016`/`2023`/`2024`), each with `· A`/`· B`, and the daily-driver star on `2012`. (Constraint #1.)

- [ ] **Step 3: Exercise each feature** (these mutate the persisted roster — they are reverted in Step 4):
  - Tap **+** in the All Batteries header → scan sheet opens; a known battery shows dimmed "Added as …".
  - Long-press a row → menu shows Pin / Rename battery / Change group / Rename group / Remove battery.
  - Rename a battery; move it to another group; create a new group and assign it; rename a group.
  - Swipe a row left → trash icon appears → release → confirmation dialog → Cancel (row stays).
  - Single-tap a row → detail page shows identity (alias + real name + MAC + group), per-cell voltages, telemetry, and the "History — coming soon" section. Back returns to Home.
  - Remove a battery via the menu (confirm) and via swipe (confirm).

- [ ] **Step 4: Restore the seed (REQUIRED).** Clear the persisted roster so it re-seeds from `DEFAULT_ROSTER`:

Run: `adb shell pm clear dev.joely.bmsmon`
(Or, if only the roster should reset while keeping other settings, uninstall/reinstall: `adb uninstall dev.joely.bmsmon && ./gradlew :app:installDebug`.)

Relaunch and confirm the roster is back to the exact seed: 4 groups of 2, labels `2012/2016/2023/2024`, aliases `· A`/`· B`, daily driver `2012`. (Constraints #1 and #2.)

- [ ] **Step 5: Final commit** (none needed if no files changed; verification only).

---

## Self-Review

**Spec coverage:**
- Empty roster → big `+` on stage — Task 10. ✓
- `+` top-right of All Batteries when ≥1 battery — Task 8 (header always shows `+`; with an empty roster the stage `+` is the primary path). ✓
- Scan, compare MACs, dim known with "Added as xxxx" — Tasks 6, 7. ✓
- Remove via long-press menu w/ confirm — Task 8. ✓
- Swipe-left w/ trash icon + confirm — Task 8. ✓
- Add to group (existing/new), change group, rename battery, rename group — Tasks 5 (VM) + 8 (UI). ✓
- Single-tap detail w/ real-name↔alias, per-cell, all safe info, graph placeholder — Tasks 5 (nav), 9. ✓
- 3+ packs on stage — Task 10. ✓
- Seed from current list + preserve names/groups + restore after testing — Tasks 1 (seed + drift test), 4/5 (persist/fallback), 12 (restore). ✓
- Compatible-only scan (safety) — Task 6. ✓

**Placeholder scan:** "History — coming soon" is the only placeholder and is an intentional spec deliverable (future graphs), not a plan gap. No "TBD"/"add error handling"/"similar to Task N".

**Type consistency:** `Battery`/`Group`/`Roster` and the extension names (`groupById`, `groupViews`, `groupOf`, `allTargets`, `targetFor`, `batteryAt`, `addBattery`, `removeBattery`, `renameBattery`, `assignGroup`, `addGroup`, `renameGroup`) are defined in Tasks 1-2 and consumed with the same names in Tasks 3-11. VM methods (`addBattery`, `removeBattery`, `renameBattery`, `setBatteryGroup`, `createGroupForBattery`, `renameGroup`, `openDetail`, `closeDetail`) defined in Task 5 are consumed in Tasks 8/11. `StageInputs` trailing `groups` param defined in Task 3, used in Task 5's `refresh` and Task 3's test. `BmsRepository.setTargets` defined in Task 5 Step 1, used in Step 8. Consistent.

**Build-ordering note:** Tasks 1-2 are written against the new `BatteryGroup(id, label, targets)` shape and do not compile until Task 3 lands; this is called out in each task and the first green build is at Task 3 Step 4 (unit tests) / Task 5 Step 10 (full module). This is intentional to keep each commit a coherent unit.
