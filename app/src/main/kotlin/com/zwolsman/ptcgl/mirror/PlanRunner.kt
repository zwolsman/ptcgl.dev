package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.harvester.plan.PlanService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PlanRunner::class.java)

/**
 * Phase A pipeline runner.
 *
 * Flags:
 *   --plan              required to activate
 *   --latest            process only the set with the most recent release date
 *   --set=<code>        process only the named set (e.g. --set=sv8)
 *
 * Example:
 *   SPRING_PROFILES_ACTIVE=local RAINIER_PTCS_TOKEN=<token> \
 *     ./gradlew :app:bootRun --args='--plan --latest'
 */
@Component
@Order(10)
class PlanRunner(private val planService: PlanService) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("plan")) return

        val setFilter = args.getOptionValues("set")?.firstOrNull()
        val latestOnly = args.containsOption("latest")

        log.info("Starting Phase A plan… (setFilter={}, latestOnly={})", setFilter, latestOnly)
        planService.run(locale = "en", setFilter = setFilter, latestOnly = latestOnly)
        log.info("Phase A plan complete.")
    }
}
