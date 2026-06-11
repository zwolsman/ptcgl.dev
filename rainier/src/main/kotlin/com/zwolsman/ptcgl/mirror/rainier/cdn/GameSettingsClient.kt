package com.zwolsman.ptcgl.mirror.rainier.cdn

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GameSettingsClient::class.java)
private val mapper = jacksonObjectMapper()

@JsonIgnoreProperties(ignoreUnknown = true)
data class GameSettings(
    val data: Map<String, String>,
) {
    /** Returns the Unity content path for the given platform, e.g. "osxplayer". */
    fun contentPath(platform: String): String =
        data["${platform}_contentpath"]
            ?: error("GameSettings missing key '${platform}_contentpath'. Keys: ${data.keys.filter { it.contains("contentpath") }}")
}

/**
 * Fetches GameSettings.json from the public CDN.
 * URL: {cdnBase}/rainier/GameSettings/{appVersion}/GameSettings.json
 *
 * GameSettings.json is publicly readable — no auth required.
 * It contains per-platform content paths for Unity asset bundle downloads.
 */
class GameSettingsClient(
    private val http: OkHttpClient,
    private val cdnBase: String = "https://cdn.studio-prod.pokemon.com",
) {
    fun fetch(appVersion: String): GameSettings {
        val url = "$cdnBase/rainier/GameSettings/$appVersion/GameSettings.json"
        log.debug("Fetching GameSettings from {}", url)
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GameSettings GET $url failed ${response.code}" }
            val body = response.body?.string() ?: error("Empty GameSettings response")
            return mapper.readValue(body)
        }
    }
}
