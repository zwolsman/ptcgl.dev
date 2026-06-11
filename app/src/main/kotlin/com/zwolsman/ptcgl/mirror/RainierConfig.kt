package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.cdn.CdnClient
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val log = LoggerFactory.getLogger(RainierConfig::class.java)

@Configuration
class RainierConfig(
    @Value("\${rainier.base-url}")               private val baseUrl: String,
    @Value("\${rainier.client-type-access-key}") private val clientTypeAccessKey: String,
    @Value("\${rainier.client-id}")              private val clientId: String,
    @Value("\${rainier.ptcs-token}")             private val ptcsToken: String,
    @Value("\${rainier.content-path}")           private val contentPath: String,
) {

    @Bean
    fun okHttpClient(): OkHttpClient = OkHttpClient()

    @Bean
    fun configDocClient(http: OkHttpClient): ConfigDocClient {
        log.info("Authenticating with Rainier API…")
        val session = AuthClient(http, clientTypeAccessKey, clientId, baseUrl).authenticate(ptcsToken)
        log.info("Authenticated, API endpoint: {}", session.apiEndpoint)
        return ConfigDocClient(http, session.apiEndpoint, session.studioToken)
    }

    @Bean
    fun cdnClient(http: OkHttpClient): CdnClient = CdnClient(http, contentPath)
}
