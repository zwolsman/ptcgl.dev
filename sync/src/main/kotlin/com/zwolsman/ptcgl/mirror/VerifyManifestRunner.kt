package com.zwolsman.ptcgl.mirror

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.rainier.cdn.CdnClient
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import com.zwolsman.ptcgl.unity.bundle.UnityBundle
import com.zwolsman.ptcgl.unity.manifest.AssetManifestExtractor
import com.zwolsman.ptcgl.unity.serialized.SerializedFileParser
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Lazy
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger(VerifyManifestRunner::class.java)
private val mapper = jacksonObjectMapper()

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
    @Lazy private val configClient: ConfigDocClient,
    private val cdnClient: CdnClient,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("verify-manifest")) return

        log.info("Fetching asset-bundle-manifest_0.0…")
        val manifestDoc = configClient.getMultiple("asset-bundle-manifest_0.0").first()
        log.info("Revision: {}", manifestDoc.revision)

        val manifestEntry = manifestDoc["manifest"]
            ?: error("Key 'manifest' not found. Keys: ${manifestDoc.data.keys}")

        @Suppress("UNCHECKED_CAST")
        val buckets = (mapper.readValue(manifestEntry.payloadBase64, Map::class.java)["directories"] as? List<*>)
            ?.filterIsInstance<String>()
            ?: error("manifest JSON missing 'directories' array")

        log.info("Found {} buckets: {}", buckets.size, buckets)

        val locale = "en"
        val fixtureDir = Path.of("unity/src/test/resources/fixtures")
        Files.createDirectories(fixtureDir)

        var saved = 0
        for (bucket in buckets) {
            log.info("Trying bucket={} locale={}", bucket, locale)

            val bundleBytes = try {
                cdnClient.downloadManifestBundle(bucket, locale)
            } catch (e: com.zwolsman.ptcgl.mirror.rainier.cdn.AssetNotFoundException) {
                log.info("  {} — not found, skipping", bucket)
                continue
            }

            log.info("Downloaded {} bytes from bucket={}", bundleBytes.size, bucket)

            val fixturePath = fixtureDir.resolve("manifest-${bucket}-${locale}.bundle")
            Files.write(fixturePath, bundleBytes)
            log.info("Bundle saved to {}", fixturePath)

            val files = UnityBundle.parse(bundleBytes)
            log.info("Bundle contains {} file(s): {}", files.size, files.map { it.path })

            val sf = files.firstOrNull()?.let { SerializedFileParser.parse(it.data) }
            if (sf == null) { log.warn("Bundle is empty, skipping"); continue }
            log.info("SerializedFile: version={}, types={}, objects={}", sf.version, sf.types.size, sf.objects.size)

            val entries = AssetManifestExtractor.extract(sf)
            log.info("Parsed {} manifest entries. First 3:", entries.size)
            entries.take(3).forEach { log.info("  {}", it) }

            saved++
            if (saved >= 1) break
        }
        log.info("Saved {} fixture bundle(s)", saved)
    }
}
