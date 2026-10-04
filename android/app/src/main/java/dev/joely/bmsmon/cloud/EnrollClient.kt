package dev.joely.bmsmon.cloud

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A failed enrollment: [code] is the HTTP status (null = the server was never reached), [detail] the
 * app's `{"detail"}` string (≤ 200 chars). Neither ever carries key material or the code.
 */
class EnrollException(val code: Int?, val detail: String?, cause: Throwable? = null) :
    Exception("enroll failed ${code ?: "without a response"}${detail?.let { ": $it" } ?: ""}", cause)

/** One sentence the user can act on, for any enrollment failure (DATA-25). Never echoes the server's text. */
internal fun enrollErrorMessage(t: Throwable): String = when {
    t is EnrollException -> when (t.code) {
        null -> "Couldn't reach the server. Check the server URL and this phone's connection."
        400, 422 ->
            if (t.detail?.startsWith("bad public key") == true) {
                "The server rejected this phone's upload key. Try again; if it keeps failing, restart the phone."
            } else {
                "That enrollment code is invalid or has expired. Make a new one on the web dashboard."
            }
        403 ->
            if (t.detail?.contains("revoked") == true) {
                "This phone was revoked. An admin can restore it in the web dashboard (Settings › Devices); " +
                    "no new code is needed."
            } else {
                "Enrollment failed (HTTP 403)."
            }
        429 -> "Too many enrollment attempts. Wait a few minutes, then try again."
        in 500..599 -> "The server is having trouble (HTTP ${t.code}). Try again in a few minutes."
        else -> "Enrollment failed (HTTP ${t.code})."
    }
    t is IllegalArgumentException -> "That server URL isn't valid."
    t is java.security.GeneralSecurityException || t is java.security.ProviderException ->
        "This phone couldn't create its upload key. Try again; if it keeps failing, restart the phone."
    else -> "Enrollment failed: the server's answer wasn't understood."
}

class EnrollClient(private val http: OkHttpClient) {
    suspend fun enroll(baseUrl: String, code: String, installUuid: String,
                       publicKeySpkiB64: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("code", code)
                put("install_uuid", installUuid)
                put("public_key_spki_b64", publicKeySpkiB64)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(CloudConfig(baseUrl).enrollUrl).post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext Result.failure(EnrollException(resp.code, apiDetail(text)))
                val id = ((Json.parseToJsonElement(text) as JsonObject)["device_id"] as JsonPrimitive).content
                Result.success(id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(EnrollException(null, null, e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
