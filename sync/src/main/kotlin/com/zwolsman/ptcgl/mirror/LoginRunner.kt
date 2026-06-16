package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.harvester.db.AuthState
import com.zwolsman.ptcgl.mirror.harvester.db.AuthStateRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(LoginRunner::class.java)

/**
 * Seeds auth_state with the initial (or replacement) PTCS refresh token.
 *
 * Usage:
 *   SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun \
 *     --args='--login --refresh-token=<token>'
 *
 * The access token is left null so that AuthService performs the first refresh
 * (and persists the rotated refresh token) on the next run.
 *
 * Run this again to re-seed after a reuse-detected failure.
 */
@Component
@Order(0)
class LoginRunner(private val authStateRepo: AuthStateRepository) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("login")) return

        val refreshToken = args.getOptionValues("refresh-token")?.firstOrNull()
            ?: error("--refresh-token=<token> is required when using --login")

        authStateRepo.upsert(AuthState(
            refreshToken          = refreshToken,
            refreshTokenUpdatedAt = Instant.now(),
            accessToken           = null,
            accessTokenExpiresAt  = null,
            lastStudioIss         = null,
        ))

        log.info("auth_state seeded with new refresh token. Run without --login to authenticate.")
    }
}
