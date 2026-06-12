package com.zwolsman.ptcgl.mirror

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private val log = LoggerFactory.getLogger(VerifyCardDbRunner::class.java)
private val mapper = jacksonObjectMapper()

/**
 * Debug runner: downloads a card DB and saves it as a test fixture.
 *
 * Disabled unless --verify-card-db is passed on the command line.
 *
 * Usage:
 *   SPRING_PROFILES_ACTIVE=local RAINIER_PTCS_TOKEN=<token> \
 *     ./gradlew :app:bootRun --args='--verify-card-db'
 */
@Component
class VerifyCardDbRunner(private val configClient: ConfigDocClient) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("verify-card-db")) return

        // --- Discover card DB templates ---
        log.info("Fetching card-databases-manifest_0.0…")
        val manifestDoc = configClient.getMultiple("card-databases-manifest_0.0").first()
        log.info("Manifest revision: {}", manifestDoc.revision)

        val manifestKey = manifestDoc["card-databases-manifest"]
            ?: error("Key 'card-databases-manifest' not found. Keys: ${manifestDoc.data.keys}")

        val templates = mapper.readValue<List<String>>(manifestKey.contentString ?: manifestKey.payloadBase64)
        log.info("Found {} set templates. First few: {}", templates.size, templates.take(5))

        val template = templates.firstOrNull { it.startsWith("sv1_") } ?: templates.first()
        val docId = "card-database-${template.replace("{0}", "en")}_0.0"
        log.info("Fetching card DB: {}", docId)

        // --- Fetch one card DB ---
        val cardDbDoc = configClient.getMultiple(docId).first()
        log.info("Card DB revision: {}", cardDbDoc.revision)

        val tableKey = cardDbDoc["table"]
            ?: error("Key 'table' not found. Keys present: ${cardDbDoc.data.keys}")

        // Save fixtures
        val fixtureDir = Path.of("rainier/src/test/resources/fixtures")
        Files.createDirectories(fixtureDir)
        val fixturePath = fixtureDir.resolve("card-db-sv1-en.b64")
        Files.writeString(fixturePath, tableKey.payloadBase64)
        log.info("Raw base64 payload saved to {}", fixturePath)

        val rawBytes = Base64.getDecoder().decode(tableKey.payloadBase64)
        val engineByte = rawBytes[0].toInt() and 0xFF
        log.info("Engine byte: 0x{} ({})", engineByte.toString(16), if (engineByte == 0) "raw" else "QuickLZ")
        val dataBytes = if (engineByte == 0x00) rawBytes.copyOfRange(1, rawBytes.size)
                        else com.zwolsman.ptcgl.mirror.rainier.codec.QuickLz.decompress(rawBytes.copyOfRange(1, rawBytes.size))
        Files.write(fixtureDir.resolve("card-db-sv1-en.bin"), dataBytes)
        log.info("Binary payload: {} bytes", dataBytes.size)

        // --- Decode and inspect EN ---
        log.info("Decoding DataTable…")
        val table = DataTableCodec.decodeFromBase64(tableKey.payloadBase64)

        log.info("Table name  : {}", table.name)
        log.info("Row count   : {}", table.rows.size)
        log.info("Columns ({}):", table.columns.size)
        table.columns.forEach { col -> log.info("  {} {}", col.name, col.typeName) }

        log.info("First 3 rows:")
        table.rows.take(3).forEachIndexed { i, row -> log.info("  Row {}: {}", i, row) }

        // --- Fetch and save FR fixture ---
        val frDocId = "card-database-${template.replace("{0}", "fr")}_0.0"
        log.info("Fetching FR card DB: {}", frDocId)
        val frCardDbDoc = configClient.getMultiple(frDocId).first()

        val frTableKey = frCardDbDoc["table"]
            ?: error("Key 'table' not found in FR doc. Keys present: ${frCardDbDoc.data.keys}")

        Files.writeString(fixtureDir.resolve("card-db-sv1-fr.b64"), frTableKey.payloadBase64)
        log.info("FR raw base64 payload saved to {}", fixtureDir.resolve("card-db-sv1-fr.b64"))

        val frRawBytes = Base64.getDecoder().decode(frTableKey.payloadBase64)
        val frEngineByte = frRawBytes[0].toInt() and 0xFF
        val frDataBytes = if (frEngineByte == 0x00) frRawBytes.copyOfRange(1, frRawBytes.size)
                          else com.zwolsman.ptcgl.mirror.rainier.codec.QuickLz.decompress(frRawBytes.copyOfRange(1, frRawBytes.size))
        Files.write(fixtureDir.resolve("card-db-sv1-fr.bin"), frDataBytes)
        log.info("FR binary payload: {} bytes", frDataBytes.size)

        val frTable = DataTableCodec.decodeFromBase64(frTableKey.payloadBase64)
        log.info("FR table name  : {}", frTable.name)
        log.info("FR row count   : {}", frTable.rows.size)
        log.info("FR columns ({}): {}", frTable.columns.size, frTable.columns.map { it.name })
    }
}
