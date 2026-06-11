package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import com.zwolsman.ptcgl.unity.bundle.UnityBundle
import com.zwolsman.ptcgl.unity.manifest.AssetManifestExtractor
import com.zwolsman.ptcgl.unity.serialized.SerializedFileParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger(VerifyManifestRunner::class.java)

/**
 * Debug runner: fetches a real manifest bundle from the CDN, parses it, saves as fixture.
 *
 * Disabled unless --verify-manifest is passed on the command line.
 *
 * Usage:
 *   SPRING_PROFILES_ACTIVE=local RAINIER_PTCS_TOKEN=<token> \
 *     ./gradlew :app:bootRun --args='--verify-manifest'
 */
@Component
@Order(2)
class VerifyManifestRunner(
    @Value("\${rainier.base-url}")              private val baseUrl: String,
    @Value("\${rainier.client-type-access-key}") private val clientTypeAccessKey: String,
    @Value("\${rainier.client-id}")             private val clientId: String,
    @Value("\${rainier.ptcs-token}")            private val ptcsToken: String,
    @Value("\${rainier.content-path}")          private val contentPath: String,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("verify-manifest")) return

        val http = OkHttpClient()

        log.info("Authenticating…")
        val session = AuthClient(http, clientTypeAccessKey, clientId, baseUrl).authenticate(ptcsToken)
        val configClient = ConfigDocClient(http, session.apiEndpoint, session.studioToken)

        // Fetch the bucket list from the control-plane
        log.info("Fetching asset-bundle-manifest_0.0…")
        val manifestDoc = configClient.getMultiple("asset-bundle-manifest_0.0").first()
        log.info("Revision: {}", manifestDoc.revision)

        val manifestEntry = manifestDoc["manifest"]
            ?: error("Key 'manifest' not found. Keys: ${manifestDoc.data.keys}")

        @Suppress("UNCHECKED_CAST")
        val directories = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readValue(manifestEntry.payloadBase64, Map::class.java)
            .let { (it["directories"] as? List<*>)?.filterIsInstance<String>() }
            ?: error("manifest JSON missing 'directories' array")

        log.info("Found {} buckets. First few: {}", directories.size, directories.take(5))

        // Download the first bucket's manifest bundle (locale=en)
        val bucket = directories.first()
        val locale = "en"
        val url = "${contentPath}${bucket}/manifest_${locale}_${bucket}"
        log.info("Downloading manifest bundle: {}", url)

        val bundleBytes = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "CDN GET $url failed ${resp.code}" }
            resp.body?.bytes() ?: error("Empty response body")
        }
        log.info("Downloaded {} bytes", bundleBytes.size)

        // Save fixture
        val fixtureDir = Path.of("unity/src/test/resources/fixtures")
        Files.createDirectories(fixtureDir)
        val fixturePath = fixtureDir.resolve("manifest-${bucket}-${locale}.bundle")
        Files.write(fixturePath, bundleBytes)
        log.info("Bundle saved to {}", fixturePath)

        // Parse and extract
        val files = UnityBundle.parse(bundleBytes)
        log.info("Bundle contains {} file(s): {}", files.size, files.map { it.path })

        val serializedFile = files.firstOrNull()
            ?: error("Bundle is empty")
        val sf = SerializedFileParser.parse(serializedFile.data)
        log.info("SerializedFile: version={}, types={}, objects={}", sf.version, sf.types.size, sf.objects.size)

        val entries = AssetManifestExtractor.extract(sf)
        log.info("Parsed {} manifest entries. First 3:", entries.size)
        entries.take(3).forEach { log.info("  {}", it) }
    }
}
