package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.harvester.db.AuthState
import com.zwolsman.ptcgl.mirror.harvester.db.AuthStateRepository
import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.auth.PtcsTokenClient
import com.zwolsman.ptcgl.mirror.rainier.auth.RainierHttpException
import com.zwolsman.ptcgl.mirror.rainier.auth.StudioSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

private val log = LoggerFactory.getLogger(AuthService::class.java)

@Service
class AuthService(
    private val authStateRepo: AuthStateRepository,
    private val ptcsTokenClient: PtcsTokenClient,
    private val authClient: AuthClient,
    @Value("\${rainier.client-id}") private val clientId: String,
    txManager: PlatformTransactionManager,
) {
    private val txTemplate = TransactionTemplate(txManager)

    /**
     * Returns a live studio session, refreshing the PTCS access token if needed.
     *
     * Fast path (token still valid by time): skips the OAuth2 refresh endpoint.
     *
     * Slow path (token expired or absent): acquires a SELECT … FOR UPDATE lock to
     * prevent concurrent runs from double-spending the single-use refresh token,
     * re-checks expiry after the lock, then refreshes and commits before the studio chain.
     *
     * Recovery path (token time-valid but server-side invalidated — 424 from studio):
     * forces a PTCS token refresh and retries the studio auth once.
     */
    fun acquireStudioSession(): StudioSession {
        val state = authStateRepo.find()
            ?: error("No auth_state row found. Seed with: ./gradlew :sync:bootRun --args='--login --refresh-token=<token>'")

        val accessToken = if (state.isAccessTokenValid()) {
            log.info("PTCS access token still valid (expires {}), skipping refresh", state.accessTokenExpiresAt)
            state.accessToken!!
        } else {
            log.info("PTCS access token expired or absent — acquiring lock and refreshing")
            doRefresh(force = false)
        }

        return try {
            authClient.authenticate(accessToken)
        } catch (e: RainierHttpException) {
            if (e.statusCode != 424) throw e
            log.warn("Studio auth rejected token (server-side invalidation 424); forcing PTCS refresh and retrying")
            val refreshed = doRefresh(force = true)
            authClient.authenticate(refreshed)
        }
    }

    /**
     * Runs a short isolated transaction: lock → refresh → persist new tokens → commit.
     * The commit happens before the caller continues with the studio auth chain, so a
     * failure there cannot roll back the already-rotated refresh token.
     */
    private fun doRefresh(force: Boolean): String = txTemplate.execute {
        val locked = authStateRepo.readForUpdate()
        if (!force && locked.isAccessTokenValid()) {
            log.info("Access token was refreshed by a concurrent run; reusing it")
            return@execute locked.accessToken!!
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
        tokens.accessToken
    }!!

    private fun AuthState.isAccessTokenValid(): Boolean {
        val token   = accessToken           ?: return false
        val expires = accessTokenExpiresAt  ?: return false
        return token.isNotBlank() && expires > Instant.now().plusSeconds(300)
    }
}
