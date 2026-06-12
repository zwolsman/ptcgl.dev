package com.zwolsman.ptcgl.mirror

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zwolsman.ptcgl.mirror.rainier.auth.AuthClient
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live verification test: fetches the sv1/FR card database and compares its column schema
 * and a sample row against the sv1/EN fixture to validate multi-locale assumptions.
 *
 * Run with:
 *   RAINIER_PTCS_TOKEN=<token> ./gradlew :rainier:test --tests "*FrCardDbVerify*" --info
 *
 * Skipped automatically when RAINIER_PTCS_TOKEN is not set.
 */
@EnabledIfEnvironmentVariable(named = "RAINIER_PTCS_TOKEN", matches = ".+")
class FrCardDbVerifyTest {

    private val mapper = jacksonObjectMapper()

    private val BASE_URL = "https://api.studio-prod.pokemon.com"
    private val CLIENT_TYPE_ACCESS_KEY = "421d8904-0236-4ab4-94f5-a8a84aeb3f7b"
    private val CLIENT_ID = "tpci-tcg-app"

    @Test
    fun `compare FR and EN card DB columns and sample row`() {
        val ptcsToken = System.getenv("RAINIER_PTCS_TOKEN")
        val http = OkHttpClient()

        println("=== Authenticating ===")
        val session = AuthClient(http, CLIENT_TYPE_ACCESS_KEY, CLIENT_ID, BASE_URL).authenticate(ptcsToken)
        println("API endpoint: ${session.apiEndpoint}")

        val client = ConfigDocClient(http, session.apiEndpoint, session.studioToken)

        // --- Discover the sv1 template ---
        println("\n=== Fetching card-databases-manifest ===")
        val manifestDoc = client.getMultiple("card-databases-manifest_0.0").first()
        val templates = mapper.readValue<List<String>>(
            manifestDoc["card-databases-manifest"]!!.payloadBase64
        )
        val sv1Template = templates.first { it.startsWith("sv1_") }
        println("sv1 template: $sv1Template")

        // --- Fetch EN and FR card DBs ---
        val enDocId = "card-database-${sv1Template.replace("{0}", "en")}_0.0"
        val frDocId = "card-database-${sv1Template.replace("{0}", "fr")}_0.0"
        println("\n=== Fetching EN: $enDocId ===")
        val enTable = fetchTable(client, enDocId)
        println("EN table: ${enTable.name}, ${enTable.columns.size} columns, ${enTable.rows.size} rows")

        println("\n=== Fetching FR: $frDocId ===")
        val frTable = fetchTable(client, frDocId)
        println("FR table: ${frTable.name}, ${frTable.columns.size} columns, ${frTable.rows.size} rows")

        // --- Column comparison ---
        val enCols = enTable.columns.map { it.name }.toSet()
        val frCols = frTable.columns.map { it.name }.toSet()
        val onlyInEn = enCols - frCols
        val onlyInFr = frCols - enCols

        println("\n=== Column diff ===")
        if (onlyInEn.isEmpty() && onlyInFr.isEmpty()) {
            println("Schemas are IDENTICAL — same column names in both locales.")
        } else {
            println("Only in EN: $onlyInEn")
            println("Only in FR: $onlyInFr")
        }

        println("\n=== FR columns (ordered) ===")
        frTable.columns.forEach { println("  ${it.name.padEnd(40)} ${it.typeName}") }

        // --- Same card, EN vs FR row comparison ---
        println("\n=== Side-by-side row comparison (first card in both tables) ===")
        val enRow = enTable.rows.first()
        val frRow = frTable.rows.firstOrNull { it["cardID"] == enRow["cardID"] } ?: frTable.rows.first()
        println("Card ID: ${enRow["cardID"]} / ${frRow["cardID"]}")

        val localeFields = listOf(
            "EN Card Name", "LocalizedCardName",
            "EN Attack Name", "LocalizedAttackName",
            "EN Attack Name 2", "LocalizedAttackName 2",
            "EN Attack Name 3", "LocalizedAttackName 3",
            "EN Attack Name 4", "LocalizedAttackName 4",
            "EN Attack Text", "LocalizedAttackText",
            "EN Attack Text 2", "LocalizedAttackText 2",
            "EN Attack Text 3", "LocalizedAttackText 3",
            "EN Attack Text 4", "LocalizedAttackText 4",
            "extraSearchText",
        )
        println("\n  Locale-sensitive fields:")
        for (col in localeFields) {
            val enVal = enRow[col]?.toString()?.take(80)
            val frVal = frRow[col]?.toString()?.take(80)
            if (enVal != null || frVal != null) {
                println("  [$col]")
                println("    EN: $enVal")
                println("    FR: $frVal")
            }
        }

        println("\n  Locale-invariant fields (should match):")
        val invariantFields = listOf(
            "HP", "Retreat", "Damage", "EN Cost", "EN Weakness Type", "Weakness Amount",
            "EN Type", "Regulations symbol", "Loc Rarity Code", "CompSea Card Number",
            "category", "archetypeID", "Group ID",
        )
        for (col in invariantFields) {
            val enVal = enRow[col]
            val frVal = frRow[col]
            val match = if (enVal == frVal) "OK" else "MISMATCH"
            println("  $match  [$col]  EN=$enVal  FR=$frVal")
        }

        // --- Check for any column present in FR that is absent from EN ---
        println("\n=== FR-only column values in sample row ===")
        val frOnlyOrNew = frRow.keys.filter { it !in enRow }
        if (frOnlyOrNew.isEmpty()) println("  (none — schemas identical)")
        else frOnlyOrNew.forEach { println("  [$it] = ${frRow[it]}") }
    }

    private fun fetchTable(client: ConfigDocClient, docId: String): DataTableCodec.Table {
        val doc = client.getMultiple(docId).first()
        val entry = doc["card-database"] ?: doc["table"] ?: doc.data.values.first()
        return DataTableCodec.decodeFromBase64(entry.payloadBase64)
    }
}
