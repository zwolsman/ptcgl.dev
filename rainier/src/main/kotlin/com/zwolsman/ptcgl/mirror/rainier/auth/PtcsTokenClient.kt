package com.zwolsman.ptcgl.mirror.rainier.auth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

private val mapper = jacksonObjectMapper()

data class PtcsTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
)

/**
 * Exchanges a PTCS refresh token for a fresh access token via the Pokémon OAuth2 endpoint.
 *
 * Endpoint: POST https://access.pokemon.com/oauth2/token
 * The refresh token is single-use and rotates on every successful call; the new
 * refresh_token in the response must be persisted immediately after HTTP 200.
 */
class PtcsTokenClient(
    private val http: OkHttpClient,
    private val tokenUrl: String = "https://access.pokemon.com/oauth2/token",
) {

    fun refresh(refreshToken: String, clientId: String): PtcsTokens {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            check(response.isSuccessful) {
                "PTCS token refresh failed ${response.code}: $bodyStr"
            }
            val parsed = mapper.readValue<TokenResponse>(bodyStr)
            val expiresAt = Instant.now().plusSeconds(parsed.expiresIn.toLong() - 60)
            return PtcsTokens(
                accessToken  = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresAt    = expiresAt,
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @param:JsonProperty("access_token")  val accessToken: String,
        @param:JsonProperty("refresh_token") val refreshToken: String,
        @param:JsonProperty("expires_in")    val expiresIn: Int,
    )
}
