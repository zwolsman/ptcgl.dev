package com.zwolsman.ptcgl.mirror.harvester.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class AuthState(
    val refreshToken: String,
    val refreshTokenUpdatedAt: Instant,
    val accessToken: String?,
    val accessTokenExpiresAt: Instant?,
    val lastStudioIss: String?,
)

@Repository
class AuthStateRepository(private val jdbc: JdbcTemplate) {

    private val mapper = RowMapper { rs, _ ->
        AuthState(
            refreshToken           = rs.getString("refresh_token"),
            refreshTokenUpdatedAt  = rs.getTimestamp("refresh_token_updated_at").toInstant(),
            accessToken            = rs.getString("access_token"),
            accessTokenExpiresAt   = rs.getTimestamp("access_token_expires_at")?.toInstant(),
            lastStudioIss          = rs.getString("last_studio_iss"),
        )
    }

    fun find(): AuthState? =
        jdbc.query(
            "SELECT * FROM auth_state WHERE id = 1",
            mapper,
        ).firstOrNull()

    /** Must be called inside an active transaction (e.g. from a @Transactional service method). */
    fun readForUpdate(): AuthState =
        jdbc.query(
            "SELECT * FROM auth_state WHERE id = 1 FOR UPDATE",
            mapper,
        ).firstOrNull() ?: error("auth_state row missing — seed with --login --refresh-token=<token>")

    @Transactional
    fun upsert(state: AuthState) {
        jdbc.update("""
            INSERT INTO auth_state
                (id, refresh_token, refresh_token_updated_at,
                 access_token, access_token_expires_at, last_studio_iss)
            VALUES (1, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                refresh_token            = EXCLUDED.refresh_token,
                refresh_token_updated_at = EXCLUDED.refresh_token_updated_at,
                access_token             = EXCLUDED.access_token,
                access_token_expires_at  = EXCLUDED.access_token_expires_at,
                last_studio_iss          = EXCLUDED.last_studio_iss
        """.trimIndent(),
            state.refreshToken,
            java.sql.Timestamp.from(state.refreshTokenUpdatedAt),
            state.accessToken,
            state.accessTokenExpiresAt?.let { java.sql.Timestamp.from(it) },
            state.lastStudioIss,
        )
    }
}
