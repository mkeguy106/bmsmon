package dev.joely.bmsmon.cloud

class CloudConfig(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    val ingestUrl = "$base/api/v1/ingest"
    val enrollUrl = "$base/api/v1/enroll"
}
