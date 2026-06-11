package com.zwolsman.ptcgl.mirror.rainier.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ConfigDocClient::class.java)

private val JSON = "application/json".toMediaType()
private val mapper = jacksonObjectMapper()

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConfigDocEntry(
    val contentType: String?,
    val contentString: String?,   // non-null for JSON entries
    val contentBinary: String?,   // non-null for binary entries (base64)
) {
    /** Returns the raw payload base64, regardless of whether it came from contentString or contentBinary. */
    val payloadBase64: String get() = contentBinary ?: contentString
        ?: error("ConfigDocEntry has neither contentString nor contentBinary")
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConfigDoc(
    val id: String,
    val revision: String,  // hex string, e.g. "45f39a54"
    val data: Map<String, ConfigDocEntry>,  // key name → {contentType, contentString}
) {
    operator fun get(key: String): ConfigDocEntry? = data[key]
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
            // Response: {"documents": {"<id>": {ConfigDoc}, ...}}
            val docsNode = mapper.readTree(bodyStr).get("documents")
                ?: error("Missing 'documents' field in getMultiple response")
            // Log the raw structure of each requested doc for debugging
            ids.forEach { id ->
                val docNode = docsNode.get(id)
                if (docNode != null) {
                    val dataNode = docNode.get("data")
                    log.debug("Doc '{}' data keys: {}", id, dataNode?.fieldNames()?.asSequence()?.toList())
                    dataNode?.fields()?.asSequence()?.forEach { (k, v) ->
                        log.debug("  data['{}'] field names: {} | first 200c: {}", k, v.fieldNames().asSequence().toList(), mapper.writeValueAsString(v).take(200))
                    }
                }
            }
            val docMap = mapper.convertValue(
                docsNode,
                mapper.typeFactory.constructMapType(Map::class.java, String::class.java, ConfigDoc::class.java)
            ) as Map<String, ConfigDoc>
            // Return in requested order; absent keys are an error
            return ids.map { id -> docMap[id] ?: error("Doc '$id' missing from response") }
        }
    }
}
