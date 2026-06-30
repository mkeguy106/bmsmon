# bmsmon Telemetry Cloud — Phase 2 (Phone Reporter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app report all telemetry to the Phase 1 API server — buffering offline and flushing on reconnect — with secure keypair enrollment and a one-time import of existing on-device history, all surfaced in a new "Cloud sync" settings page.

**Architecture:** A process-lifetime `TelemetryReporter` (held by `BmsApp`, injected into `MonitorEngine`) taps every sample at `MonitorEngine.onPoll()` and every BLE link transition, writing them to a new Room `outbox` table. A connectivity-gated uploader coroutine drains the outbox in signed batches to `/api/v1/ingest`; a separate resumable importer pages the existing `samples` table once at enrollment. Auth is an ECDSA P-256 keypair in the Android Keystore; each batch is an ES256 JWT the phone self-signs.

**Tech Stack:** Kotlin, Room (migration 1→2), DataStore, OkHttp, Nimbus JOSE+JWT (ES256 over a Keystore key), kotlinx.serialization, Coroutines, Jetpack Compose (Settings Hub).

## Global Constraints

- **Read-only protocol stays read-only.** This phase only *reads* telemetry already produced by the BMS and uploads it; it sends nothing new to any battery. Do not touch BLE command code.
- **Never destroy user data.** The Room schema change is an **additive migration** (`MIGRATION_1_2` creating the `outbox` table). Do NOT use `fallbackToDestructiveMigration`. Existing `samples`/`sessions`/`rawFrames` data must survive.
- Offline behavior is **silent**: no user-facing errors when uploads fail; buffer and retry. Connectivity loss must never crash or block BLE/logging.
- Auth contract MUST match the server (Phase 1): JWT alg **ES256**, claims `sub`(device_id), `iat`, `exp` (~60s), `jti` (random), `bh` = base64url(sha256(body)) **no padding**. The body hashed MUST be the exact bytes POSTed.
- Sample JSON field names sent to `/api/v1/ingest` match the server's `SampleIn`: `ts_ms, address, advertised_name, alias, group_id, state, soc, current_a, power_w, voltage_v, temp_c, mosfet_temp_c, soh, full_charge_ah, remaining_ah, cycles, cell_min_v, cell_max_v, cells, regen, link_event`.
- Device private key is generated in the **Android Keystore** (alias `bmsmon_device`), non-exportable; only the public key (SPKI) leaves the device, at enrollment.
- All changes under `android/`. Do not touch `server/` or `web/`.
- minSdk 26, targetSdk 34 (unchanged).

---

## File Structure

```
android/app/src/main/
  AndroidManifest.xml                              # + INTERNET permission
  java/dev/joely/bmsmon/
    BmsApp.kt                                      # construct reporter, inject into engine
    cloud/
      CloudConfig.kt                               # base URL + endpoints helper
      DeviceKeys.kt                                # Keystore P-256: ensure key, SPKI b64, sign ES256 JWT
      CloudJson.kt                                 # kotlinx.serialization DTOs + sample->json
      EnrollClient.kt                              # POST /api/v1/enroll (OkHttp)
      TelemetryReporter.kt                         # outbox enqueue + uploader state machine + importer
      Connectivity.kt                              # ConnectivityManager online flow
    data/db/
      OutboxEntity.kt                              # new Room entity
      OutboxDao.kt                                 # insert/peek/deleteUpTo/count
      BmsDatabase.kt                               # + OutboxEntity, version 2, MIGRATION_1_2
    data/SettingsStore.kt                          # + cloud_* keys
    BatteryViewModel.kt                            # + cloud UiState fields + setters + reporter calls
    monitor/MonitorEngine.kt                       # onPoll/link taps -> reporter
    ui/settings/CloudSyncPage.kt                   # new detail page content
    ui/settings/SettingsScreen.kt                  # new "Cloud sync" hub category + route
  app/build.gradle.kts                             # + okhttp, nimbus, serialization plugin/dep
android/app/src/test/java/dev/joely/bmsmon/cloud/  # JVM unit tests (software EC key)
```

---

## Task 1: Dependencies + INTERNET permission

**Files:**
- Modify: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/build.gradle.kts` (serialization plugin classpath if needed)

**Interfaces:**
- Produces: availability of `okhttp3`, `com.nimbusds.jose`, `kotlinx.serialization` at compile time; `android.permission.INTERNET`.

- [ ] **Step 1: Add the INTERNET permission** to `AndroidManifest.xml` next to the existing `ACCESS_NETWORK_STATE` line:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: Add the serialization plugin** to `android/app/build.gradle.kts` `plugins { }` block (alongside the existing kotlin/compose plugins):

```kotlin
    kotlin("plugin.serialization") version "1.9.22"
```
(Use the same Kotlin version already applied in the project; check the existing `kotlin(...)`/`org.jetbrains.kotlin.android` version and match it.)

- [ ] **Step 3: Add the dependencies** to the `dependencies { }` block:

```kotlin
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 4: Compile to verify the toolchain resolves**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (no code uses the new deps yet; this proves resolution + the serialization plugin apply).

- [ ] **Step 5: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/AndroidManifest.xml
git commit -m "build(android): add okhttp, nimbus-jose-jwt, serialization, INTERNET permission"
```

---

## Task 2: Cloud settings (DataStore) + UiState fields + ViewModel setters

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt`, `android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudSettingsTest.kt` (pure logic only — see note)

**Interfaces:**
- Produces (SettingsStore): keys + accessors for `cloudEnabled:Boolean`, `apiBaseUrl:String?`, `deviceId:String?`, `enrolled:Boolean`, `importWatermark:Long`, `importDone:Boolean`, `installUuid:String` (generated once); `Persisted` gains these fields; setters `setCloudEnabled/setApiBaseUrl/setDeviceId/setEnrolled/setImportWatermark/setImportDone`.
- Produces (UiState): `cloudEnabled, apiBaseUrl, enrolled, cloudOutboxDepth:Int, cloudLastUploadMs:Long, importDone, importTotal:Int, importSent:Int` (display-only; defaults off/empty).
- Produces (ViewModel): `setCloudEnabled(on)`, `setApiBaseUrl(url)` passthroughs.

- [ ] **Step 1: Add keys + Persisted fields to `SettingsStore.kt`** — follow the existing `K` object + `Persisted` + `load()` + `setXxx` pattern exactly. Add to `K`:

```kotlin
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ENROLLED = booleanPreferencesKey("enrolled")
        val IMPORT_WATERMARK = longPreferencesKey("import_watermark")
        val IMPORT_DONE = booleanPreferencesKey("import_done")
        val INSTALL_UUID = stringPreferencesKey("install_uuid")
```
Add the matching fields to `Persisted`, read them in `load()` (defaults: cloudEnabled=false, enrolled=false, importWatermark=0L, importDone=false; `installUuid` lazily generated — see Step 2), and add setters mirroring `setLogging`:

```kotlin
    suspend fun setCloudEnabled(on: Boolean) = context.dataStore.edit { it[K.CLOUD_ENABLED] = on }.let {}
    suspend fun setApiBaseUrl(url: String) = context.dataStore.edit { it[K.API_BASE_URL] = url }.let {}
    suspend fun setDeviceId(id: String) = context.dataStore.edit { it[K.DEVICE_ID] = id }.let {}
    suspend fun setEnrolled(on: Boolean) = context.dataStore.edit { it[K.ENROLLED] = on }.let {}
    suspend fun setImportWatermark(v: Long) = context.dataStore.edit { it[K.IMPORT_WATERMARK] = v }.let {}
    suspend fun setImportDone(on: Boolean) = context.dataStore.edit { it[K.IMPORT_DONE] = on }.let {}
```

- [ ] **Step 2: Add a stable install UUID accessor** to `SettingsStore.kt` (generated once, reused forever):

```kotlin
    suspend fun installUuid(): String {
        val existing = context.dataStore.data.first()[K.INSTALL_UUID]
        if (existing != null) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[K.INSTALL_UUID] = fresh }
        return fresh
    }
```

- [ ] **Step 3: Add the display-only UiState fields** to `BatteryViewModel.kt`'s `UiState` data class (defaults so nothing renders until enrolled):

```kotlin
    val cloudEnabled: Boolean = false,
    val apiBaseUrl: String? = null,
    val enrolled: Boolean = false,
    val cloudOutboxDepth: Int = 0,
    val cloudLastUploadMs: Long = 0,
    val importDone: Boolean = false,
    val importTotal: Int = 0,
    val importSent: Int = 0,
```

- [ ] **Step 4: Add ViewModel passthrough setters** following the existing `setLogging` pattern (persist + update state):

```kotlin
    fun setCloudEnabled(on: Boolean) { viewModelScope.launch { settings.setCloudEnabled(on) }; _state.update { it.copy(cloudEnabled = on) } }
    fun setApiBaseUrl(url: String) { viewModelScope.launch { settings.setApiBaseUrl(url) }; _state.update { it.copy(apiBaseUrl = url) } }
```
Also load the new persisted fields into the initial `UiState` wherever `settings.load()` is consumed (mirror how `logging`/`tempFahrenheit` are wired).

- [ ] **Step 5: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/SettingsStore.kt android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "feat(android): cloud-sync settings (DataStore + UiState + setters)"
```

> Note: DataStore reads need an Android context, so there's no pure-JVM unit test here; correctness is covered by compile + the on-device smoke in Task 8.

---

## Task 3: Device keys + ES256 JWT signer

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/cloud/DeviceKeys.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/JwtSignerTest.kt`

**Interfaces:**
- Produces:
  - `object Jwt` with `fun bodyHash(body: ByteArray): String` (base64url(sha256) no padding) and `fun signEs256(privateKey: java.security.PrivateKey, deviceId: String, body: ByteArray, nowMs: Long, ttlSec: Long = 60): String` — builds and signs the JWT with claims `sub,iat,exp,jti,bh`. **Pure/JVM-testable** (takes any `PrivateKey`).
  - `object DeviceKeys` (Android-only) with `fun ensureKeyPair(): Unit` (creates the Keystore P-256 key under alias `bmsmon_device` if absent), `fun publicKeySpkiB64(): String`, `fun privateKey(): java.security.PrivateKey` (Keystore handle), `fun hasKey(): Boolean`, `fun deleteKey()`.

- [ ] **Step 1: Write the failing test `JwtSignerTest.kt`** (uses a software P-256 key — no Keystore needed on the JVM):

```kotlin
package dev.joely.bmsmon.cloud

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class JwtSignerTest {
    private fun kp() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun bodyHash_is_unpadded_base64url_sha256() {
        val body = """{"x":1}""".toByteArray()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        assertEquals(expected, Jwt.bodyHash(body))
    }

    @Test fun signed_jwt_verifies_and_binds_body() {
        val pair = kp()
        val body = """{"batch_seq":1,"samples":[]}""".toByteArray()
        val token = Jwt.signEs256(pair.private, "dev-123", body, nowMs = 1_000_000L)
        val jwt = SignedJWT.parse(token)
        assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
        assertTrue(jwt.verify(ECDSAVerifier(pair.public as java.security.interfaces.ECPublicKey)))
        val c = jwt.jwtClaimsSet
        assertEquals("dev-123", c.subject)
        assertEquals(Jwt.bodyHash(body), c.getStringClaim("bh"))
        assertEquals(1000L, c.issueTime.time / 1000)         // iat seconds
        assertEquals(1060L, c.expirationTime.time / 1000)    // exp = iat + 60
        assertTrue(c.jwtid != null && c.jwtid.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.JwtSignerTest"`
Expected: FAIL (unresolved `Jwt`).

- [ ] **Step 3: Write `DeviceKeys.kt`**

```kotlin
package dev.joely.bmsmon.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.UUID

private const val ALIAS = "bmsmon_device"
private const val KS = "AndroidKeyStore"

/** JWT assembly + hashing — pure, JVM-testable (no Keystore dependency). */
object Jwt {
    fun bodyHash(body: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(body))

    fun signEs256(privateKey: PrivateKey, deviceId: String, body: ByteArray,
                  nowMs: Long, ttlSec: Long = 60): String {
        val claims = JWTClaimsSet.Builder()
            .subject(deviceId)
            .issueTime(Date((nowMs / 1000) * 1000))
            .expirationTime(Date(((nowMs / 1000) + ttlSec) * 1000))
            .jwtID(UUID.randomUUID().toString())
            .claim("bh", bodyHash(body))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims)
        // Nimbus ECDSASigner accepts a non-extractable PrivateKey (Keystore handle).
        jwt.sign(ECDSASigner(privateKey, Curve.P_256))
        return jwt.serialize()
    }
}

/** Android Keystore P-256 keypair for device auth. The private key never leaves the device. */
object DeviceKeys {
    private fun ks() = KeyStore.getInstance(KS).apply { load(null) }

    fun hasKey(): Boolean = ks().containsAlias(ALIAS)

    fun ensureKeyPair() {
        if (hasKey()) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS)
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    fun privateKey(): PrivateKey = (ks().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey

    fun publicKey(): PublicKey = ks().getCertificate(ALIAS).publicKey

    fun publicKeySpkiB64(): String =
        Base64.getEncoder().encodeToString(publicKey().encoded) // X.509 SubjectPublicKeyInfo (SPKI)

    fun deleteKey() { if (hasKey()) ks().deleteEntry(ALIAS) }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.JwtSignerTest"`
Expected: PASS (the `Jwt` object is pure; `DeviceKeys` is not exercised here — it needs a device).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/DeviceKeys.kt android/app/src/test/java/dev/joely/bmsmon/cloud/JwtSignerTest.kt
git commit -m "feat(android): Keystore P-256 keys + ES256 JWT signer (bh/jti/exp)"
```

---

## Task 4: Outbox Room table + DAO + additive migration

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/data/db/OutboxEntity.kt`, `android/app/src/main/java/dev/joely/bmsmon/data/db/OutboxDao.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/data/db/BmsDatabase.kt`
- Test: `android/app/src/androidTest/java/dev/joely/bmsmon/data/db/MigrationTest.kt`

**Interfaces:**
- Produces: `OutboxEntity(id, payload, enqueuedAt)`; `OutboxDao` with `suspend fun insert(rows: List<OutboxEntity>)`, `suspend fun peek(limit: Int): List<OutboxEntity>` (oldest first), `suspend fun deleteUpTo(id: Long)`, `suspend fun count(): Int`; `BmsDatabase.outbox()`, bumped to `version = 2` with `MIGRATION_1_2`.

- [ ] **Step 1: Write `OutboxEntity.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One pending upload: a fully-serialized ingest sample JSON object (no enclosing batch). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    val enqueuedAt: Long,
)
```

- [ ] **Step 2: Write `OutboxDao.kt`**

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    @Insert suspend fun insert(rows: List<OutboxEntity>)
    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit") suspend fun peek(limit: Int): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id <= :id") suspend fun deleteUpTo(id: Long)
    @Query("SELECT COUNT(*) FROM outbox") suspend fun count(): Int
    @Query("DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id ASC LIMIT :n)") suspend fun dropOldest(n: Int)
}
```

- [ ] **Step 3: Modify `BmsDatabase.kt`** — add the entity, bump version, add the DAO and an additive migration. The companion `create` registers `MIGRATION_1_2`:

```kotlin
@Database(
    entities = [SampleEntity::class, SessionEntity::class, RawFrameEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class BmsDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun sessions(): SessionDao
    abstract fun rawFrames(): RawFrameDao
    abstract fun outbox(): OutboxDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS outbox " +
                        "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, payload TEXT NOT NULL, enqueuedAt INTEGER NOT NULL)"
                )
            }
        }

        fun create(context: Context): BmsDatabase =
            Room.databaseBuilder(context, BmsDatabase::class.java, "bms.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
```

- [ ] **Step 4: Write the migration test `MigrationTest.kt`** (instrumented — proves v1 data survives v2):

```kotlin
package dev.joely.bmsmon.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BmsDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrate1To2_keeps_samples_and_adds_outbox() {
        val name = "migtest.db"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO samples (address, tsMs, sessionId, regen) VALUES ('AA', 1, 1, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, BmsDatabase.MIGRATION_1_2)
        val s = db.query("SELECT COUNT(*) FROM samples"); s.moveToFirst(); assertTrue(s.getInt(0) == 1)
        val o = db.query("SELECT COUNT(*) FROM outbox"); o.moveToFirst(); assertTrue(o.getInt(0) == 0)
    }
}
```
Add `androidTestImplementation("androidx.room:room-testing:2.6.1")` to `build.gradle.kts` if not present.

- [ ] **Step 5: Verify**

Run: `cd android && ./gradlew :app:compileDebugKotlin` (KSP regenerates the Room schema; must succeed). If an emulator/device is attached: `./gradlew :app:connectedDebugAndroidTest --tests "dev.joely.bmsmon.data.db.MigrationTest"` → PASS. If no device, note that the migration test requires one and verify compile only.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/data/db/ android/app/build.gradle.kts android/app/src/androidTest
git commit -m "feat(android): outbox table + DAO + additive 1->2 migration"
```

---

## Task 5: Cloud JSON DTOs + enrollment client

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/cloud/CloudConfig.kt`, `CloudJson.kt`, `EnrollClient.kt`
- Test: `android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt`

**Interfaces:**
- Produces:
  - `CloudConfig(baseUrl: String)` with `ingestUrl: String`, `enrollUrl: String` (trim trailing slash; append `/api/v1/...`).
  - `@Serializable SampleJson(...)` (all server `SampleIn` fields, nullable where the server allows) and `IngestBody(batch_seq: Int, samples: List<SampleJson>)`; `object CloudJson { val json = Json { encodeDefaults = true; explicitNulls = false }; fun encodeBatch(seq: Int, rows: List<String>): ByteArray }` where `rows` are pre-serialized sample JSON strings (wrap them as `{"batch_seq":seq,"samples":[ ...rows... ]}` and return UTF-8 bytes); `fun sampleJson(...)->String`.
  - `EnrollClient(http: OkHttpClient)` with `suspend fun enroll(baseUrl: String, code: String, installUuid: String, publicKeySpkiB64: String): Result<String>` returning the `device_id` on success.

- [ ] **Step 1: Write the failing test `CloudJsonTest.kt`**

```kotlin
package dev.joely.bmsmon.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudJsonTest {
    @Test fun config_builds_endpoints_trimming_slash() {
        val c = CloudConfig("https://bmsmon.covert.life/")
        assertEquals("https://bmsmon.covert.life/api/v1/ingest", c.ingestUrl)
        assertEquals("https://bmsmon.covert.life/api/v1/enroll", c.enrollUrl)
    }

    @Test fun sampleJson_emits_server_field_names() {
        val s = CloudJson.sampleJson(
            tsMs = 1719686400000, address = "C8:47:80:15:67:44", advertisedName = "R-12100",
            alias = "2012 · A", groupId = "2012", state = "Discharging", soc = 87f, currentA = -2.5f,
            powerW = 127.5f, voltageV = 51f, tempC = 25f, mosfetTempC = 28, soh = 98,
            fullChargeAh = 100f, remainingAh = 87.5f, cycles = 342, cellMinV = 3.17f, cellMaxV = 3.19f,
            regen = false, linkEvent = null)
        assertTrue(s.contains("\"ts_ms\":1719686400000"))
        assertTrue(s.contains("\"current_a\":-2.5"))
        assertTrue(s.contains("\"group_id\":\"2012\""))
        assertTrue(!s.contains("\"link_event\""))   // explicitNulls=false drops nulls
    }

    @Test fun encodeBatch_wraps_rows() {
        val body = CloudJson.encodeBatch(7, listOf("""{"ts_ms":1,"address":"A"}"""))
        val str = String(body)
        assertEquals("""{"batch_seq":7,"samples":[{"ts_ms":1,"address":"A"}]}""", str)
    }
}
```

- [ ] **Step 2: Run it (RED)**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"` → FAIL.

- [ ] **Step 3: Write `CloudConfig.kt`**

```kotlin
package dev.joely.bmsmon.cloud

class CloudConfig(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    val ingestUrl = "$base/api/v1/ingest"
    val enrollUrl = "$base/api/v1/enroll"
}
```

- [ ] **Step 4: Write `CloudJson.kt`**

```kotlin
package dev.joely.bmsmon.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SampleJson(
    val ts_ms: Long,
    val address: String,
    val advertised_name: String? = null,
    val alias: String? = null,
    val group_id: String? = null,
    val state: String? = null,
    val soc: Float? = null,
    val current_a: Float? = null,
    val power_w: Float? = null,
    val voltage_v: Float? = null,
    val temp_c: Float? = null,
    val mosfet_temp_c: Int? = null,
    val soh: Int? = null,
    val full_charge_ah: Float? = null,
    val remaining_ah: Float? = null,
    val cycles: Int? = null,
    val cell_min_v: Float? = null,
    val cell_max_v: Float? = null,
    val regen: Boolean = false,
    val link_event: String? = null,
)

object CloudJson {
    val json = Json { encodeDefaults = true; explicitNulls = false }

    @Suppress("LongParameterList")
    fun sampleJson(
        tsMs: Long, address: String, advertisedName: String?, alias: String?, groupId: String?,
        state: String?, soc: Float?, currentA: Float?, powerW: Float?, voltageV: Float?, tempC: Float?,
        mosfetTempC: Int?, soh: Int?, fullChargeAh: Float?, remainingAh: Float?, cycles: Int?,
        cellMinV: Float?, cellMaxV: Float?, regen: Boolean, linkEvent: String?,
    ): String = json.encodeToString(
        SampleJson.serializer(),
        SampleJson(tsMs, address, advertisedName, alias, groupId, state, soc, currentA, powerW,
            voltageV, tempC, mosfetTempC, soh, fullChargeAh, remainingAh, cycles, cellMinV, cellMaxV,
            regen, linkEvent),
    )

    /** Wrap pre-serialized sample JSON object strings into the ingest batch body bytes. */
    fun encodeBatch(seq: Int, rows: List<String>): ByteArray =
        ("""{"batch_seq":$seq,"samples":[""" + rows.joinToString(",") + "]}").toByteArray()
}
```

- [ ] **Step 5: Write `EnrollClient.kt`**

```kotlin
package dev.joely.bmsmon.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class EnrollClient(private val http: OkHttpClient) {
    suspend fun enroll(baseUrl: String, code: String, installUuid: String,
                       publicKeySpkiB64: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject(mapOf(
                "code" to code, "install_uuid" to installUuid,
                "public_key_spki_b64" to publicKeySpkiB64,
            )).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(CloudConfig(baseUrl).enrollUrl).post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "enroll failed ${resp.code}: $text" }
                JSONObject(text).getString("device_id")
            }
        }
    }
}
```

- [ ] **Step 6: Run the test (GREEN) + compile**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.joely.bmsmon.cloud.CloudJsonTest"` → PASS, then `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/CloudConfig.kt android/app/src/main/java/dev/joely/bmsmon/cloud/CloudJson.kt android/app/src/main/java/dev/joely/bmsmon/cloud/EnrollClient.kt android/app/src/test/java/dev/joely/bmsmon/cloud/CloudJsonTest.kt
git commit -m "feat(android): cloud JSON DTOs + enrollment client"
```

---

## Task 6: TelemetryReporter — outbox enqueue + connectivity-gated uploader

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/cloud/Connectivity.kt`, `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/BmsApp.kt`, `android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt`

**Interfaces:**
- Consumes: `OutboxDao`, `CloudJson`, `Jwt`, `DeviceKeys`, `SettingsStore`, `CloudConfig`.
- Produces:
  - `Connectivity(context)` with `val online: StateFlow<Boolean>` (registers a default-network callback).
  - `TelemetryReporter(appContext, db, settings)` with:
    - `fun report(addr, advertisedName, alias, groupId, t: Telemetry, tsMs, regen)` — serialize → enqueue to outbox (fire-and-forget on an IO channel, never blocks `onPoll`).
    - `fun reportLink(addr, alias, groupId, connected: Boolean, tsMs)` — enqueue a link-event row (`link_event` = "Connected"/"Disconnected", telemetry fields null).
    - `fun start()` — launch the uploader loop (idempotent); `fun stop()`.
    - The uploader: while enabled+enrolled, await (online && outbox non-empty), `peek(BATCH)`, build batch bytes, sign ES256 JWT, POST `/api/v1/ingest`; on HTTP 200 `deleteUpTo(maxId)`; on failure exponential backoff (cap 60s). Outbox capacity cap `OUTBOX_MAX` (drop oldest if exceeded). Updates `state.cloudOutboxDepth`/`cloudLastUploadMs` via a callback.
- Engine wiring: `MonitorEngine` gains an optional `reporter: TelemetryReporter?`; `onPoll` calls `reporter?.report(...)` after the `t != null` check; the link sink (next to `repository.logLink(...)`) calls `reporter?.reportLink(...)`.

- [ ] **Step 1: Write `Connectivity.kt`**

```kotlin
package dev.joely.bmsmon.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Connectivity(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { _online.value = cm.activeNetwork != null }
        })
        _online.value = cm.activeNetwork != null
    }
}
```

- [ ] **Step 2: Write `TelemetryReporter.kt`**

```kotlin
package dev.joely.bmsmon.cloud

import android.content.Context
import dev.joely.bmsmon.data.SettingsStore
import dev.joely.bmsmon.data.db.BmsDatabase
import dev.joely.bmsmon.data.db.OutboxEntity
import dev.joely.bmsmon.model.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

private const val BATCH = 200
private const val OUTBOX_MAX = 200_000

class TelemetryReporter(
    appContext: Context,
    private val db: BmsDatabase,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val conn = Connectivity(appContext)
    private val enqueue = Channel<OutboxEntity>(Channel.UNLIMITED)
    @Volatile private var started = false
    var onStatus: ((depth: Int, lastUploadMs: Long) -> Unit)? = null

    init {
        // single-writer drain of the enqueue channel into the outbox (never blocks callers)
        scope.launch { for (row in enqueue) runCatching { db.outbox().insert(listOf(row)) } }
    }

    fun report(addr: String, advertisedName: String?, alias: String?, groupId: String?,
               t: Telemetry, tsMs: Long, regen: Boolean) {
        val payload = CloudJson.sampleJson(tsMs, addr, advertisedName, alias, groupId,
            t.state.name, t.soc, t.current, t.powerW, t.voltage, t.temp, t.mosfetTemp, t.soh,
            t.fullChargeAh, t.capacityAh, t.cycles,
            t.cells.minOrNull(), t.cells.maxOrNull(), regen, null)
        enqueue.trySend(OutboxEntity(payload = payload, enqueuedAt = tsMs))
    }

    fun reportLink(addr: String, alias: String?, groupId: String?, connected: Boolean, tsMs: Long) {
        val payload = CloudJson.sampleJson(tsMs, addr, null, alias, groupId, null, null, null, null,
            null, null, null, null, null, null, null, null, null, false,
            if (connected) "Connected" else "Disconnected")
        enqueue.trySend(OutboxEntity(payload = payload, enqueuedAt = tsMs))
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { uploadLoop() }
    }

    private suspend fun uploadLoop() {
        var backoff = 1000L
        var seq = 0
        while (true) {
            val p = settings.load()
            val base = p.apiBaseUrl
            if (!p.cloudEnabled || !p.enrolled || p.deviceId == null || base == null) { delay(2000); continue }
            // cap the outbox
            val depth = db.outbox().count()
            if (depth > OUTBOX_MAX) db.outbox().dropOldest(depth - OUTBOX_MAX)
            if (!conn.online.value || depth == 0) { onStatus?.invoke(depth, p.cloudLastUploadMsOrZero()); delay(1500); continue }
            val rows = db.outbox().peek(BATCH)
            if (rows.isEmpty()) { delay(1500); continue }
            seq += 1
            val body = CloudJson.encodeBatch(seq, rows.map { it.payload })
            val ok = runCatching {
                val token = Jwt.signEs256(DeviceKeys.privateKey(), p.deviceId!!, body, System.currentTimeMillis())
                val req = Request.Builder().url(CloudConfig(base).ingestUrl)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                db.outbox().deleteUpTo(rows.last().id)
                settings.setImportTouchUpload(System.currentTimeMillis())
                onStatus?.invoke(db.outbox().count(), System.currentTimeMillis())
                backoff = 1000L
            } else {
                delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun stop() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren() }
}
```
(If `Persisted` lacks `cloudLastUploadMsOrZero()`/`setImportTouchUpload`, replace those with a no-op or a simple `settings.setLastUploadMs`; the status callback is display-only — keep it minimal and consistent with what Task 2 actually persists. Use the real `Telemetry` field names from `model/Telemetry.kt`: `soc, powerW, current, voltage, capacityAh, temp, soh, cycles, state, fullChargeAh, mosfetTemp, cells`.)

- [ ] **Step 2b: Self-check the `Telemetry` field mapping** against `android/app/src/main/java/dev/joely/bmsmon/model/Telemetry.kt` and fix any name/type mismatch (e.g. `mosfetTemp:Int`, `cells:List<Float>`). The `remaining_ah` server field maps from `capacityAh`; `cell_min_v`/`cell_max_v` from `cells.minOrNull()/maxOrNull()`.

- [ ] **Step 3: Wire into `BmsApp.kt`**

```kotlin
class BmsApp : Application() {
    val settings by lazy { dev.joely.bmsmon.data.SettingsStore(this) }
    val reporter by lazy {
        TelemetryReporter(applicationContext,
            dev.joely.bmsmon.data.db.BmsDatabase.create(applicationContext), settings)
    }
    val engine: MonitorEngine by lazy { MonitorEngine(applicationContext, reporter) }
}
```
(If a `BmsDatabase` instance is already created inside `MonitorEngine`, share ONE instance: construct the DB in `BmsApp` and pass it to both the engine's `TelemetryRepository` and the reporter, OR keep the engine's DB and pass `reporter` only — avoid opening `bms.db` twice. Pick the approach that matches the engine's current constructor; document the choice in the task report.)

- [ ] **Step 4: Tap `MonitorEngine`** — add `reporter: TelemetryReporter? = null` constructor param; in `onPoll`, after the `t` null-check and regen computation, call:

```kotlin
        reporter?.report(addr, roster.batteryAt(addr)?.advertisedName, roster.batteryAt(addr)?.alias,
            group?.id, t, now, regen)
```
and at the link sink (where `repository.logLink(addr, reachable, ...)` is called) add:

```kotlin
        reporter?.reportLink(addr, roster.batteryAt(addr)?.alias, roster.groupOf(addr)?.id, reachable, now())
```
Start the uploader from `BmsApp`/engine init (`reporter?.start()` once monitoring starts, or unconditionally in `BmsApp` — uploader self-guards on `cloudEnabled && enrolled`).

- [ ] **Step 5: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL. Fix any `Telemetry`/`Roster` accessor mismatches the compiler flags.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/ android/app/src/main/java/dev/joely/bmsmon/BmsApp.kt android/app/src/main/java/dev/joely/bmsmon/monitor/MonitorEngine.kt
git commit -m "feat(android): TelemetryReporter outbox + connectivity-gated signed uploader"
```

---

## Task 7: One-time resumable historical importer

**Files:**
- Modify: `android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt` (add importer), `android/app/src/main/java/dev/joely/bmsmon/data/db/Daos.kt` (paging query)

**Interfaces:**
- Consumes: `SampleDao` (existing samples), `SettingsStore.importWatermark/importDone`, the same signed-POST path as the uploader.
- Produces: `SampleDao.pageAfter(afterId: Long, limit: Int): List<SampleEntity>` (ordered by id ASC); `TelemetryReporter.runImport()` — pages existing `samples` after the watermark, serializes each to the same sample JSON (deriving `address/alias/group_id` from the roster, `link_event=null`), POSTs in signed batches, advances `importWatermark`, sets `importDone` when drained. Idempotent on the server (`ON CONFLICT DO NOTHING`); throttled below the live uploader.

- [ ] **Step 1: Add the paging query** to `SampleDao` (in `Daos.kt`):

```kotlin
    @Query("SELECT * FROM samples WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<SampleEntity>
```

- [ ] **Step 2: Add `runImport()` to `TelemetryReporter`** — launched once when enrolled and `!importDone`. Maps `SampleEntity` columns to the server field names (the entity stores `currentA/powerW/voltageV/tempC/mosfetTempC/soh/fullChargeAh/remainingAh/cycles/cellMinV/cellMaxV/regen/state/linkEvent/address/tsMs`), resolves `alias/group_id` from `roster.batteryAt(address)`, and reuses the signed-batch POST. Pseudocode (fill with the real columns):

```kotlin
    suspend fun runImport(roster: dev.joely.bmsmon.model.Roster) {
        val p = settings.load()
        if (!p.enrolled || p.importDone || p.deviceId == null || p.apiBaseUrl == null) return
        var after = p.importWatermark
        while (true) {
            val page = db.samples().pageAfter(after, 500)
            if (page.isEmpty()) { settings.setImportDone(true); break }
            val rows = page.map { e ->
                val b = roster.batteryAt(e.address)
                CloudJson.sampleJson(e.tsMs, e.address, b?.advertisedName, b?.alias, b?.groupId,
                    e.state, e.soc, e.currentA, e.powerW, e.voltageV, e.tempC, e.mosfetTempC, e.soh,
                    e.fullChargeAh, e.remainingAh, e.cycles, e.cellMinV, e.cellMaxV, e.regen, e.linkEvent)
            }
            val body = CloudJson.encodeBatch(-1, rows)   // seq -1 marks import; server ignores seq value
            val token = Jwt.signEs256(DeviceKeys.privateKey(), p.deviceId, body, System.currentTimeMillis())
            val ok = postSigned(CloudConfig(p.apiBaseUrl).ingestUrl, token, body)
            if (!ok) { delay(5000); continue }            // retry the same page
            after = page.last().id
            settings.setImportWatermark(after)
            onImportProgress?.invoke(after)
            delay(750)                                    // throttle below live path
        }
    }
```
Extract the signed POST into a private `postSigned(url, token, body): Boolean` reused by both the live loop and the importer (DRY).

- [ ] **Step 3: Compile** — `cd android && ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL (fix any `SampleEntity` column-name mismatches the compiler flags against `data/db/SampleEntity.kt`).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/cloud/TelemetryReporter.kt android/app/src/main/java/dev/joely/bmsmon/data/db/Daos.kt
git commit -m "feat(android): one-time resumable historical importer"
```

---

## Task 8: "Cloud sync" settings page + hub category + end-to-end smoke

**Files:**
- Create: `android/app/src/main/java/dev/joely/bmsmon/ui/settings/CloudSyncPage.kt`
- Modify: `android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt` (new hub category + route), `android/app/src/main/java/dev/joely/bmsmon/ui/App.kt` (pass enroll/cloud callbacks if needed), `BatteryViewModel.kt` (an `enroll(baseUrl, code)` action)

**Interfaces:**
- Consumes: the Settings Hub structure (`SettingsPage` enum, `GroupedCard`/`CategoryRow`/`DetailScaffold`/`ToggleRow`/`SelectChip` from `SettingsScreen.kt`), `UiState` cloud fields, ViewModel cloud setters.
- Produces: a new `SettingsPage.Cloud` category row in the hub ("Cloud sync", value = enrolled-state summary) and a `CloudSyncPage` detail composable: status block (Enrolled to `<url>` / Not enrolled, outbox depth, last upload, import progress), a server-URL field + enrollment-code field + **Enroll** button, a **Report to cloud** toggle (`cloudEnabled`), and **Forget device** (clears keys + settings). `BatteryViewModel.enroll(baseUrl, code)` ensures the Keystore key, calls `EnrollClient`, persists `deviceId/enrolled/apiBaseUrl`, and kicks `reporter.start()` + `runImport(roster)`.

- [ ] **Step 1: Add the ViewModel enroll action** to `BatteryViewModel.kt`:

```kotlin
    fun enroll(baseUrl: String, code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DeviceKeys.ensureKeyPair()
            val installUuid = settings.installUuid()
            val app = getApplication<BmsApp>()
            val res = EnrollClient(OkHttpClient()).enroll(baseUrl, code, installUuid, DeviceKeys.publicKeySpkiB64())
            res.onSuccess { id ->
                settings.setApiBaseUrl(baseUrl); settings.setDeviceId(id)
                settings.setEnrolled(true); settings.setCloudEnabled(true)
                _state.update { it.copy(apiBaseUrl = baseUrl, enrolled = true, cloudEnabled = true) }
                app.reporter.start(); app.reporter.runImport(_state.value.roster)
            }
        }
    }
    fun forgetDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            DeviceKeys.deleteKey()
            settings.setEnrolled(false); settings.setCloudEnabled(false); settings.setDeviceId("")
            settings.setImportDone(false); settings.setImportWatermark(0)
            _state.update { it.copy(enrolled = false, cloudEnabled = false) }
        }
    }
```
(`BatteryViewModel` must extend `AndroidViewModel`/have application access for `getApplication<BmsApp>()`; if it currently doesn't, thread `BmsApp.reporter` in via the existing construction path rather than changing the base class.)

- [ ] **Step 2: Write `CloudSyncPage.kt`** — a `ColumnScope.CloudSyncContent(state, onEnroll, onSetCloudEnabled, onForget)` mirroring the existing detail-page composables (use `PlainCard`, `GroupedCard`, `ToggleRow`, `SectionLabel`, `BmSwitch`, the `Bm` tokens, `MonoFont`). Sections: **Status** (enrolled/not, `state.apiBaseUrl`, outbox depth `state.cloudOutboxDepth`, last upload, import `state.importSent`/`state.importTotal` when importing); **Connection** (server URL `TextField` + enrollment-code `TextField` + Enroll button — only when not enrolled); **Reporting** (`Report to cloud` toggle bound to `cloudEnabled`); **Forget device** (destructive, `AlertCritical`). Keep inputs as Compose `OutlinedTextField`s styled to the app's input tokens.

- [ ] **Step 3: Add the hub category + route** in `SettingsScreen.kt`: add `Cloud` to the `SettingsPage` enum; a `CategoryRow(Icons.Filled.CloudUpload, CatBlue, "Cloud sync", cloudValue(state)) { onOpen(SettingsPage.Cloud) }` in the last grouped card (next to About); and the `when` branch rendering `DetailScaffold("Cloud sync", { page = null }) { CloudSyncContent(state, onEnroll, onSetCloudEnabled, onForget) }`. Add a `cloudValue(state)` helper ("Enrolled · <host>" / "Not set up"). Thread the new callbacks (`onEnroll`, `onSetCloudEnabled`, `onForget`) through `SettingsScreen(...)` params and `App.kt`'s call site (bound to `vm::enroll`, `vm::setCloudEnabled`, `vm::forgetDevice`).

- [ ] **Step 4: Compile + build the APK**

Run: `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: On-device end-to-end smoke** (emulator or the Pixel; the Phase 1 server must be reachable):
  1. Start the Phase 1 server locally (`cd server && BMSMON_DEV_TRUST_HEADERS=1 BMSMON_WEB_DIST=$PWD/../web/dist PYTHONPATH=. .venv/bin/uvicorn app.main:app --port 8000`) and mint a code (`curl -XPOST -H 'X-authentik-username: dev' -H 'X-authentik-groups: Covert.life - Full App Access - User Group' http://<host>:8000/web/enroll-codes`).
  2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`; open Settings → Cloud sync; enter the server URL (`http://<host-ip>:8000`) + code; Enroll. Confirm "Enrolled".
  3. With monitoring on (or the fake feeder unnecessary — the phone is the source), confirm the server's `/web/fleet` shows the phone's packs and the WebUI updates live; toggle airplane mode to confirm the outbox grows then drains on reconnect.
  Capture evidence (screenshots / `adb logcat` lines / `/web/fleet` output).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/joely/bmsmon/ui/settings/CloudSyncPage.kt android/app/src/main/java/dev/joely/bmsmon/ui/settings/SettingsScreen.kt android/app/src/main/java/dev/joely/bmsmon/ui/App.kt android/app/src/main/java/dev/joely/bmsmon/BatteryViewModel.kt
git commit -m "feat(android): Cloud sync settings page (enroll, report toggle, import progress)"
```

---

## Self-Review (completed during planning)

- **Spec coverage:** outbox + connectivity-gated batched flush with backoff (T4, T6); Keystore P-256 + ES256 self-signed JWT matching the server contract (T3); enrollment + key exchange (T5, T8); one-time resumable idempotent historical import (T7); Cloud sync settings page in the hub (T8); INTERNET + deps (T1); cloud settings/state (T2). Read-only-protocol and never-destroy-data constraints are encoded as the additive migration (T4) and the no-BMS-write scope.
- **Placeholder scan:** the two integration-shaped steps (engine DB-sharing in T6 Step 3; exact `Telemetry`/`SampleEntity` column names in T6/T7) are called out as explicit self-check steps against named files rather than left vague, because those accessors must be read from the live code — every other step carries complete code.
- **Type consistency:** `Jwt.signEs256`/`bodyHash`, `CloudJson.sampleJson`/`encodeBatch`, `OutboxDao` methods, and the `SettingsStore` cloud keys are referenced with identical signatures across tasks.

## Out of scope (Phase 3)

NAS deployment: `qnap-nas-docker/bmsmon/` compose with the Traefik `/api/` Authentik-bypass split + Authentik/DNS labels, `.env` (`BMSMON_DB_PASSWORD`, `BMSMON_ADMIN_GROUP`), and Uptime Kuma monitors — so `bmsmon.covert.life` serves the WebUI behind Authentik while the phone reaches `/api/` with its device JWT.
