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
 * Activated with --plan on the command line:
 *   SPRING_PROFILES_ACTIVE=local RAINIER_PTCS_TOKEN=<token> \
 *     ./gradlew :app:bootRun --args='--plan'
 */
@Component
@Order(10)
class PlanRunner(private val planService: PlanService) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("plan")) return
        log.info("Starting Phase A plan…")
        planService.run(locale = "en")
        log.info("Phase A plan complete.")
    }
}
