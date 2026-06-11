package com.zwolsman.ptcgl.mirror

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import java.net.URI

private val log = LoggerFactory.getLogger(S3Config::class.java)

@Configuration
class S3Config(
    @Value("\${mirror.s3.endpoint}")                  private val endpoint: String,
    @Value("\${mirror.s3.access-key}")                private val accessKey: String,
    @Value("\${mirror.s3.secret-key}")                private val secretKey: String,
    @Value("\${mirror.s3.region}")                    private val region: String,
    @Value("\${mirror.s3.force-path-style:false}")    private val forcePathStyle: Boolean,
    @Value("\${mirror.s3.bucket}")                    private val bucket: String,
) {

    @Bean
    fun s3Client(): S3Client {
        val s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .region(Region.of(region))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(forcePathStyle).build())
            .httpClient(UrlConnectionHttpClient.create())
            .build()

        ensureBucket(s3)
        return s3
    }

    private fun ensureBucket(s3: S3Client) {
        try {
            s3.headBucket { it.bucket(bucket) }
            log.info("S3 bucket '{}' exists", bucket)
        } catch (e: S3Exception) {
            if (e.statusCode() == 404 || e.statusCode() == 301) {
                s3.createBucket { it.bucket(bucket) }
                log.info("Created S3 bucket '{}'", bucket)
            } else {
                throw e
            }
        }
    }
}
