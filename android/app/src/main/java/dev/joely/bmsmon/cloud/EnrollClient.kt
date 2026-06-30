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
