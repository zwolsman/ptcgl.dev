package com.zwolsman.ptcgl.mirror.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsConfig(
    @Value("\${mirror.api.cors-origins:}") private val allowedOrigins: String,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        if (allowedOrigins.isBlank()) return
        val origins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        registry.addMapping("/**")
            .allowedOrigins(*origins)
            .allowedMethods("GET", "OPTIONS")
    }
}
