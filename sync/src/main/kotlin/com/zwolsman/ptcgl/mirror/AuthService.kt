package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.harvester.db.AuthState
import com.zwolsman.ptcgl.mirror.harvester.db.AuthStateRepository
import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.auth.PtcsTokenClient
import com.zwolsman.ptcgl.mirror.rainier.auth.StudioSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(AuthService::class.java)

@Service
class AuthService(
    private val authStateRepo: AuthStateRepository,
    private val ptcsTokenClient: PtcsTokenClient,
    private val authClient: AuthClient,
    @Value("\${rainier.client-id}") private val clientId: String,
) {

    /**
     * Returns a live studio session, refreshing the PTCS access token if needed.
     *
     * Fast path (token still valid by time): skips the OAuth2 refresh endpoint.
     *
     * Slow path (token expired or absent): acquires a SELECT … FOR UPDATE lock to
     * prevent concurrent runs from double-spending the single-use refresh token,
     * re-checks expiry after the lock, then refreshes.
     *
     * Recovery path (token time-valid but server-side invalidated — 424 from studio):
     * forces a PTCS token refresh and retries the studio auth once.
     */
    @Transactional
    fun acquireStudioSession(): StudioSession {
        val state = authStateRepo.find()
            ?: error("No auth_state row found. Seed with: ./gradlew :app:bootRun --args='--login --refresh-token=<token>'")

        val accessToken = if (state.isAccessTokenValid()) {
            log.info("PTCS access token still valid (expires {}), skipping refresh", state.accessTokenExpiresAt)
            state.accessToken!!
        } else {
            log.info("PTCS access token expired or absent — acquiring lock and refreshing")
            doRefresh(force = false)
        }

        return try {
            authClient.authenticate(accessToken)
        } catch (e: IllegalStateException) {
            if ("424" !in e.message.orEmpty()) throw e
            log.warn("Studio auth rejected token (server-side invalidation 424); forcing PTCS refresh and retrying")
            val refreshed = doRefresh(force = true)
            authClient.authenticate(refreshed)
        }
    }

    private fun doRefresh(force: Boolean): String {
        val locked = authStateRepo.readForUpdate()
        if (!force && locked.isAccessTokenValid()) {
            log.info("Access token was refreshed by a concurrent run; reusing it")
            return locked.accessToken!!
        }
        log.info("Calling PTCS refresh endpoint…")
        val tokens = ptcsTokenClient.refresh(locked.refreshToken, clientId)
        log.info("Refresh successful; new access token expires {}", tokens.expiresAt)
        authStateRepo.upsert(locked.copy(
            accessToken           = tokens.accessToken,
            accessTokenExpiresAt  = tokens.expiresAt,
            refreshToken          = tokens.refreshToken,
            refreshTokenUpdatedAt = Instant.now(),
        ))
        return tokens.accessToken
    }

    private fun AuthState.isAccessTokenValid(): Boolean {
        val token   = accessToken           ?: return false
        val expires = accessTokenExpiresAt  ?: return false
        return token.isNotBlank() && expires > Instant.now().plusSeconds(300)
    }
}
