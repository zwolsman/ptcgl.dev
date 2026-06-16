package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.harvester.plan.PlanService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SyncRunner::class.java)

/**
 * Phase A pipeline runner.
 *
 * Flags:
 *   --sync              required to activate
 *   --latest            process only the set with the most recent release date
 *   --set=<code>        process only the named set (e.g. --set=sv8)
 *   --locale=<code>     locale to use (default: en)
 *   --force             reprocess card databases even if their revision is unchanged
 *
 * Example:
 *   SPRING_PROFILES_ACTIVE=local RAINIER_PTCS_TOKEN=<token> \
 *     ./gradlew :sync:bootRun --args='--sync --latest --locale=fr'
 */
@Component
@Order(10)
class SyncRunner(private val planService: PlanService) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("sync")) return

        val setFilter = args.getOptionValues("set")?.firstOrNull()
        val latestOnly = args.containsOption("latest")
        val locale = args.getOptionValues("locale")?.firstOrNull() ?: "en"
        val force = args.containsOption("force")

        log.info("Starting sync… (locale={}, setFilter={}, latestOnly={}, force={})", locale, setFilter, latestOnly, force)
        planService.run(locale = locale, setFilter = setFilter, latestOnly = latestOnly, force = force)
        log.info("Sync complete.")
    }
}
