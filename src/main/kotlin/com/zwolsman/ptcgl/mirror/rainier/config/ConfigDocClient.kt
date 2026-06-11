package com.zwolsman.ptcgl.mirror.rainier.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON = "application/json".toMediaType()
private val mapper = jacksonObjectMapper()

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConfigDocKey(
    val key: String,
    val contentType: String,
    val contentString: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConfigDoc(
    val id: String,
    val revision: Long,
    val keys: List<ConfigDocKey>,
) {
    fun key(name: String): ConfigDocKey? = keys.find { it.key == name }
}

class ConfigDocClient(
    private val http: OkHttpClient,
    private val apiEndpoint: String,
    private val studioToken: String,
) {
    /** Fetch one or more config documents by ID. Returns results in the same order. */
    fun getMultiple(vararg ids: String): List<ConfigDoc> {
        val body = mapper.writeValueAsString(
            mapOf("requests" to ids.map { mapOf("id" to it) })
        )
        val request = Request.Builder()
            .url("$apiEndpoint/config/v1/external/configdocument/getMultiple")
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $studioToken")
            .build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            check(response.isSuccessful) {
                "getMultiple failed ${response.code}: $bodyStr"
            }
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class Response(val responses: List<ConfigDoc>)
            return mapper.readValue<Response>(bodyStr).responses
        }
    }
}
