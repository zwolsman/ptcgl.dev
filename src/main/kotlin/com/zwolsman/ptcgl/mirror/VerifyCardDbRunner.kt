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

        val manifestKey = manifestDoc["card-databases-manifest"]
            ?: error("Key 'card-databases-manifest' not found. Keys: ${manifestDoc.data.keys}")

        // Templates look like "sv1_1_{0}" — pick first as the probe
        val templates = mapper.readValue<List<String>>(manifestKey.contentString ?: manifestKey.payloadBase64)
        log.info("Found {} set templates. First few: {}", templates.size, templates.take(5))

        // Pick sv1 if available, otherwise the first template
        val template = templates.firstOrNull { it.startsWith("sv1_") } ?: templates.first()
        val docId = "card-database-${template.replace("{0}", "en")}_0.0"
        log.info("Fetching card DB: {}", docId)

        // --- Fetch one card DB ---
        val cardDbDoc = configClient.getMultiple(docId).first()
        log.info("Card DB revision: {}", cardDbDoc.revision)

        val tableKey = cardDbDoc["table"]
            ?: error("Key 'table' not found. Keys present: ${cardDbDoc.data.keys}")

        // Save raw base64 payload as a fixture for offline testing
        val fixturePath = Path.of("src/test/resources/fixtures/card-db-sv1-en.b64")
        Files.createDirectories(fixturePath.parent)
        Files.writeString(fixturePath, tableKey.payloadBase64)
        log.info("Raw base64 payload saved to {}", fixturePath)

        // Also save the decoded DataTable bytes for easier debugging
        val rawBytes = Base64.getDecoder().decode(tableKey.payloadBase64)
        val engineByte = rawBytes[0].toInt() and 0xFF
        log.info("Engine byte: 0x{} ({})", engineByte.toString(16), if (engineByte == 0) "raw" else "QuickLZ")
        val dataBytes = if (engineByte == 0x00) rawBytes.copyOfRange(1, rawBytes.size)
                        else com.zwolsman.ptcgl.mirror.rainier.codec.QuickLz.decompress(rawBytes.copyOfRange(1, rawBytes.size))
        Files.write(Path.of("src/test/resources/fixtures/card-db-sv1-en.bin"), dataBytes)
        log.info("Binary payload: {} bytes", dataBytes.size)

        // --- Decode and inspect ---
        log.info("Decoding DataTable…")
        val table = DataTableCodec.decodeFromBase64(tableKey.payloadBase64)

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
