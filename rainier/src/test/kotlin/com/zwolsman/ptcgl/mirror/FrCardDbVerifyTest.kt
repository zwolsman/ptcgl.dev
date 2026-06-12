package com.zwolsman.ptcgl.mirror

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.codec.DotNetBinaryReader
import com.zwolsman.ptcgl.mirror.rainier.codec.QuickLz
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import okhttp3.OkHttpClient
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertTrue

/**
 * Compares sv1/FR and sv1/EN card DB fixtures to validate multi-locale assumptions.
 *
 * Fixture generation (requires RAINIER_PTCS_TOKEN):
 *   RAINIER_PTCS_TOKEN=<token> ./gradlew :rainier:test --tests "*FrCardDbVerify*" --rerun-tasks
 *
 * The generator test (@Order(0)) runs first when the token is present and writes:
 *   src/test/resources/fixtures/card-db-sv1-en.bin
 *   src/test/resources/fixtures/card-db-sv1-fr.bin
 *
 * All other tests load from those files and skip silently when either is absent.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FrCardDbVerifyTest {

    private val fixtureBinEn = Path.of("src/test/resources/fixtures/card-db-sv1-en.bin")
    private val fixtureBinFr = Path.of("src/test/resources/fixtures/card-db-sv1-fr.bin")

    private val mapper = jacksonObjectMapper()

    // -------------------------------------------------------------------------
    // Fixture generation — runs only when RAINIER_PTCS_TOKEN is set
    // -------------------------------------------------------------------------

    @Test
    @Order(0)
    @EnabledIfEnvironmentVariable(named = "RAINIER_PTCS_TOKEN", matches = ".+")
    fun `generate EN and FR sv1 fixtures from live API`() {
        val http = OkHttpClient()
        val session = AuthClient(
            http,
            clientTypeAccessKey = "421d8904-0236-4ab4-94f5-a8a84aeb3f7b",
            clientId = "tpci-tcg-app",
            baseUrl = "https://api.studio-prod.pokemon.com",
        ).authenticate(System.getenv("RAINIER_PTCS_TOKEN"))

        val client = ConfigDocClient(http, session.apiEndpoint, session.studioToken)

        val manifestDoc = client.getMultiple("card-databases-manifest_0.0").first()
        val templates = mapper.readValue<List<String>>(
            manifestDoc["card-databases-manifest"]!!.payloadBase64
        )
        val sv1Template = templates.first { it.startsWith("sv1_") }

        val fixtureDir = Path.of("src/test/resources/fixtures")
        Files.createDirectories(fixtureDir)

        for ((locale, outName) in listOf("en" to "card-db-sv1-en", "fr" to "card-db-sv1-fr")) {
            val docId = "card-database-${sv1Template.replace("{0}", locale)}_0.0"
            val doc = client.getMultiple(docId).first()
            val entry = doc["table"] ?: doc.data.values.first()

            Files.writeString(fixtureDir.resolve("$outName.b64"), entry.payloadBase64)

            val raw = Base64.getDecoder().decode(entry.payloadBase64)
            val dataBytes = if (raw[0].toInt() and 0xFF == 0x00)
                raw.copyOfRange(1, raw.size)
            else
                QuickLz.decompress(raw.copyOfRange(1, raw.size))
            Files.write(fixtureDir.resolve("$outName.bin"), dataBytes)

            val table = DataTableCodec.parse(DotNetBinaryReader(dataBytes))
            println("Saved $outName.bin — ${table.name}, ${table.columns.size} columns, ${table.rows.size} rows")
        }
    }

    // -------------------------------------------------------------------------
    // Fixture-based comparison tests
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `FR and EN fixtures have the same row count`() {
        if (Files.notExists(fixtureBinEn) || Files.notExists(fixtureBinFr)) return
        val en = parse(fixtureBinEn)
        val fr = parse(fixtureBinFr)
        assertTrue(en.rows.isNotEmpty(), "EN table must have rows")
        assertTrue(en.rows.size == fr.rows.size,
            "Row count mismatch: EN=${en.rows.size} FR=${fr.rows.size}")
    }

    @Test
    @Order(2)
    fun `FR has more columns than EN (locale-prefixed attack columns)`() {
        if (Files.notExists(fixtureBinEn) || Files.notExists(fixtureBinFr)) return
        val en = parse(fixtureBinEn)
        val fr = parse(fixtureBinFr)

        val enCols = en.columns.map { it.name }.toSet()
        val frCols = fr.columns.map { it.name }.toSet()
        val onlyInFr = frCols - enCols

        println("EN columns: ${en.columns.size}, FR columns: ${fr.columns.size}")
        println("Only in FR: $onlyInFr")

        assertTrue(fr.columns.size > en.columns.size,
            "FR DataTable should have more columns than EN (locale-prefixed attack columns)")
        assertTrue("FR Attack Name" in onlyInFr,
            "Expected 'FR Attack Name' column. Only-in-FR: $onlyInFr")
        assertTrue("FR Attack Text" in onlyInFr,
            "Expected 'FR Attack Text' column. Only-in-FR: $onlyInFr")
        assertTrue("FR Card Name" in onlyInFr,
            "Expected 'FR Card Name' column. Only-in-FR: $onlyInFr")
    }

    @Test
    @Order(3)
    fun `EN columns are a strict subset of FR columns`() {
        if (Files.notExists(fixtureBinEn) || Files.notExists(fixtureBinFr)) return
        val en = parse(fixtureBinEn)
        val fr = parse(fixtureBinFr)

        val onlyInEn = en.columns.map { it.name }.toSet() - fr.columns.map { it.name }.toSet()
        assertTrue(onlyInEn.isEmpty(),
            "All EN columns should be present in FR table. Missing from FR: $onlyInEn")
    }

    @Test
    @Order(4)
    fun `LocalizedCardName is locale-specific in both fixtures`() {
        if (Files.notExists(fixtureBinEn) || Files.notExists(fixtureBinFr)) return
        val en = parse(fixtureBinEn)
        val fr = parse(fixtureBinFr)

        val enRow = en.rows.first()
        val cardId = enRow["cardID"]
        val frRow = fr.rows.first { it["cardID"] == cardId }

        val enName = enRow["LocalizedCardName"]?.toString()
        val frName = frRow["LocalizedCardName"]?.toString()

        println("EN LocalizedCardName: $enName")
        println("FR LocalizedCardName: $frName")

        assertTrue(enName != null && enName.isNotBlank(), "EN LocalizedCardName must be present")
        assertTrue(frName != null && frName.isNotBlank(), "FR LocalizedCardName must be present")
        assertTrue(enName != frName,
            "LocalizedCardName should differ between locales for card $cardId: EN=$enName FR=$frName")
    }

    @Test
    @Order(5)
    fun `FR attack names differ from EN attack names`() {
        if (Files.notExists(fixtureBinEn) || Files.notExists(fixtureBinFr)) return
        val en = parse(fixtureBinEn)
        val fr = parse(fixtureBinFr)

        val enRowWithAttack = en.rows.first { it["EN Attack Name"]?.toString()?.isNotBlank() == true }
        val cardId = enRowWithAttack["cardID"]
        val frRow = fr.rows.first { it["cardID"] == cardId }

        val enName = enRowWithAttack["EN Attack Name"]?.toString()
        val frName = frRow["FR Attack Name"]?.toString()

        println("Card: $cardId  EN='$enName'  FR='$frName'")

        assertTrue(frName != null && frName.isNotBlank(),
            "'FR Attack Name' must be non-blank in FR fixture for card $cardId")
        assertTrue(enName != frName,
            "FR attack name should differ from EN for card $cardId")
    }

    @Test
    @Order(6)
    fun `locale-invariant fields are identical between EN and FR`() {
        if (Files.notExists(fixtureBinEn) || Files.notExists(fixtureBinFr)) return
        val en = parse(fixtureBinEn)
        val fr = parse(fixtureBinFr)

        val invariantCols = listOf(
            "HP", "Retreat", "Damage", "EN Cost",
            "EN Weakness Type", "Weakness Amount",
            "EN Type", "Regulations symbol",
            "Loc Rarity Code", "CompSea Card Number",
            "category", "archetypeID",
        )

        val mismatches = mutableListOf<String>()
        for (enRow in en.rows) {
            val cardId = enRow["cardID"] ?: continue
            val frRow = fr.rows.firstOrNull { it["cardID"] == cardId } ?: continue
            for (col in invariantCols) {
                if (enRow[col] != frRow[col])
                    mismatches += "card=$cardId col=$col EN=${enRow[col]} FR=${frRow[col]}"
            }
        }

        assertTrue(mismatches.isEmpty(),
            "Locale-invariant fields must be identical between EN and FR:\n${mismatches.joinToString("\n")}")
    }

    private fun parse(path: Path): DataTableCodec.Table =
        DataTableCodec.parse(DotNetBinaryReader(Files.readAllBytes(path)))
}
