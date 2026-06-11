package com.zwolsman.ptcgl.mirror

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class])
class MirrorApplication

fun main(args: Array<String>) {
	runApplication<MirrorApplication>(*args)
}
