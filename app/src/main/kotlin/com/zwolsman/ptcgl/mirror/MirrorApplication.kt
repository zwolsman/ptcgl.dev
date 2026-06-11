package com.zwolsman.ptcgl.mirror

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MirrorApplication

fun main(args: Array<String>) {
	runApplication<MirrorApplication>(*args)
}
