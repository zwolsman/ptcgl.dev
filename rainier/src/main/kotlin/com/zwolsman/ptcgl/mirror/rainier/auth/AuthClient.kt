package com.zwolsman.ptcgl.mirror.rainier.auth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

private val JSON = "application/json".toMediaType()
private val mapper = jacksonObjectMapper()
private val log = LoggerFactory.getLogger(AuthClient::class.java)

data class StudioSession(
    val apiEndpoint: String,
    val guestToken: String,
    val studioToken: String,
)

class AuthClient(
    private val http: OkHttpClient,
    private val clientTypeAccessKey: String,
    private val clientId: String,
    private val baseUrl: String = "https://api.studio-prod.pokemon.com",
) {

    /** Full auth chain: routing → guest → studio JWT. */
    fun authenticate(ptcsAccessToken: String): StudioSession {
        val apiEndpoint = route()
        log.debug("Routed to {}", apiEndpoint)

        val guestToken = register(apiEndpoint)
        log.debug("Guest token acquired")

        val studioToken = exchangeForStudio(apiEndpoint, guestToken, ptcsAccessToken)
        log.debug("Studio token acquired")

        return StudioSession(apiEndpoint, guestToken, studioToken)
    }

    /** Step 1: resolve regional API endpoint. */
    private fun route(): String {
        val body = mapper.writeValueAsString(mapOf("clientTypeAccessKey" to clientTypeAccessKey))
        val response = post("$baseUrl/user/v1/external/routing/route", body, bearer = null)
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class RouteResponse(val apiEndpoint: String)
        return mapper.readValue<RouteResponse>(response).apiEndpoint
    }

    /** Step 2: register a guest token. */
    private fun register(apiEndpoint: String): String {
        val body = mapper.writeValueAsString(
            mapOf("clientTypeAccessKey" to clientTypeAccessKey, "clientId" to clientId)
        )
        val response = post("$apiEndpoint/account/v1/external/token/register", body, bearer = null)
        return extractToken(response)
    }

    /** Step 3: exchange PTCS token for a studio JWT. */
    private fun exchangeForStudio(apiEndpoint: String, guestToken: String, ptcsAccessToken: String): String {
        val body = mapper.writeValueAsString(
            mapOf("authToken" to ptcsAccessToken, "authType" to "PTOK")
        )
        val response = post("$apiEndpoint/account/v1/external/token/auth", body, bearer = guestToken)
        return extractToken(response)
    }

    private fun extractToken(responseJson: String): String {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class TokenResponse(val token: String? = null, val accessToken: String? = null)
        val r = mapper.readValue<TokenResponse>(responseJson)
        return r.token ?: r.accessToken
            ?: error("No token field found in response: $responseJson")
    }

    private fun post(url: String, jsonBody: String, bearer: String?): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (bearer != null) reqBuilder.header("Authorization", "Bearer $bearer")
        val request = reqBuilder.build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) throw RainierHttpException(response.code, "POST $url failed ${response.code}: $bodyStr")
            return bodyStr
        }
    }
}
