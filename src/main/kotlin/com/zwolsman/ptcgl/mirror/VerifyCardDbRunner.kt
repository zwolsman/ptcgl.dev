package com.zwolsman.ptcgl.mirror

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private val log = LoggerFactory.getLogger(VerifyCardDbRunner::class.java)
private val mapper = jacksonObjectMapper()

@Component
class VerifyCardDbRunner(
    @Value("\${rainier.base-url}") private val baseUrl: String,
    @Value("\${rainier.client-type-access-key}") private val clientTypeAccessKey: String,
    @Value("\${rainier.client-id}") private val clientId: String,
    @Value("\${rainier.ptcs-token}") private val ptcsToken: String,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val http = OkHttpClient()

        // --- Auth ---
        log.info("Authenticating…")
        val auth = AuthClient(http, clientTypeAccessKey, clientId, baseUrl)
        val session = auth.authenticate(ptcsToken)
        log.info("Studio token acquired, endpoint: {}", session.apiEndpoint)

        val configClient = ConfigDocClient(http, session.apiEndpoint, session.studioToken)

        // --- Discover card DB templates ---
        log.info("Fetching card-databases-manifest_0.0…")
        val manifestDoc = configClient.getMultiple("card-databases-manifest_0.0").first()
        log.info("Manifest revision: {}", manifestDoc.revision)

        val manifestKey = manifestDoc.key("card-databases-manifest")
            ?: error("Key 'card-databases-manifest' not found in doc. Keys present: ${manifestDoc.keys.map { it.key }}")

        // Templates look like "sv1_1_{0}" — pick first as the probe
        val templates = mapper.readValue<List<String>>(manifestKey.contentString)
        log.info("Found {} set templates. First few: {}", templates.size, templates.take(5))

        // Pick sv1 if available, otherwise the first template
        val template = templates.firstOrNull { it.startsWith("sv1_") } ?: templates.first()
        val docId = "card-database-${template.replace("{0}", "en")}_0.0"
        log.info("Fetching card DB: {}", docId)

        // --- Fetch one card DB ---
        val cardDbDoc = configClient.getMultiple(docId).first()
        log.info("Card DB revision: {}", cardDbDoc.revision)

        val tableKey = cardDbDoc.key("table")
            ?: error("Key 'table' not found. Keys present: ${cardDbDoc.keys.map { it.key }}")

        // Save raw base64 payload as a fixture for offline testing
        val fixturePath = Path.of("src/test/resources/fixtures/card-db-sv1-en.b64")
        Files.createDirectories(fixturePath.parent)
        Files.writeString(fixturePath, tableKey.contentString)
        log.info("Raw base64 payload saved to {}", fixturePath)

        // Also save the decompressed bytes for easier debugging
        val rawBytes = Base64.getDecoder().decode(tableKey.contentString)
        val decompressed = com.zwolsman.ptcgl.mirror.rainier.codec.QuickLz.decompress(rawBytes)
        Files.write(Path.of("src/test/resources/fixtures/card-db-sv1-en.bin"), decompressed)
        log.info("Decompressed {} bytes → {} bytes", rawBytes.size, decompressed.size)

        // --- Decode and inspect ---
        log.info("Decoding DataTable…")
        val table = DataTableCodec.decodeFromBase64(tableKey.contentString)

        log.info("Table name  : {}", table.name)
        log.info("Row count   : {}", table.rows.size)
        log.info("Columns ({}):", table.columns.size)
        table.columns.forEach { col ->
            log.info("  {:40s} {}", col.name, col.typeName)
        }

        log.info("First 3 rows:")
        table.rows.take(3).forEachIndexed { i, row ->
            log.info("  Row {}: {}", i, row)
        }
    }
}
