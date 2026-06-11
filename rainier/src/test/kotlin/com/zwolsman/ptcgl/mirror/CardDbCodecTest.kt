package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.codec.DotNetBinaryReader
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Codec tests backed by the live-fetched SV1/EN card database fixture.
 * Run VerifyCardDbRunner once against the live server to produce:
 *   src/test/resources/fixtures/card-db-sv1-en.b64   (raw base64 payload)
 *   src/test/resources/fixtures/card-db-sv1-en.bin   (DataTable bytes, engine byte stripped)
 */
class CardDbCodecTest {

    private val fixtureBin = Path.of("src/test/resources/fixtures/card-db-sv1-en.bin")
    private val fixtureB64 = Path.of("src/test/resources/fixtures/card-db-sv1-en.b64")

    @Test
    fun `payload engine byte is 0x00 (raw, not QuickLZ compressed)`() {
        if (Files.notExists(fixtureB64)) return
        val raw = Base64.getDecoder().decode(Files.readString(fixtureB64).trim())
        assertEquals(0x00, raw[0].toInt() and 0xFF,
            "Expected engine byte 0x00 (uncompressed). Actual: 0x${(raw[0].toInt() and 0xFF).toString(16)}")
    }

    @Test
    fun `DataTable binary size matches fixture`() {
        if (Files.notExists(fixtureBin)) return
        val bytes = Files.readAllBytes(fixtureBin)
        assertTrue(bytes.isNotEmpty())
        // From live run: 246602 bytes
        assertTrue(bytes.size > 200_000, "Expected >200k bytes, got ${bytes.size}")
    }

    @Test
    fun `DataTable parses column names and row count`() {
        if (Files.notExists(fixtureBin)) return
        val table = DataTableCodec.parse(DotNetBinaryReader(Files.readAllBytes(fixtureBin)))

        println("Table  : ${table.name}")
        println("Columns: ${table.columns.size}")
        table.columns.forEach { println("  ${it.name.padEnd(40)} ${it.typeName}") }
        println("Rows   : ${table.rows.size}")
        println("Row[0] : ${table.rows.firstOrNull()}")

        assertTrue(table.columns.isNotEmpty(), "Table must have columns")
        assertTrue(table.rows.isNotEmpty(), "Table must have rows")
    }

    @Test
    fun `DataTable table name is SV1_EN`() {
        if (Files.notExists(fixtureBin)) return
        val table = DataTableCodec.parse(DotNetBinaryReader(Files.readAllBytes(fixtureBin)))
        assertEquals("SV1_EN", table.name)
    }

    @Test
    fun `DataTable contains expected card columns`() {
        if (Files.notExists(fixtureBin)) return
        val table = DataTableCodec.parse(DotNetBinaryReader(Files.readAllBytes(fixtureBin)))
        val colNames = table.columns.map { it.name }.toSet()
        // Columns mentioned in DESIGN.md §9a
        val expected = setOf("cardID", "EN Card Name", "EN Card #")
        val missing = expected - colNames
        assertTrue(missing.isEmpty(), "Missing expected columns: $missing. Got: $colNames")
    }

    @Test
    fun `decodeFromBase64 full pipeline matches parse on bin`() {
        if (Files.notExists(fixtureB64) || Files.notExists(fixtureBin)) return
        val fromB64 = DataTableCodec.decodeFromBase64(Files.readString(fixtureB64).trim())
        val fromBin = DataTableCodec.parse(DotNetBinaryReader(Files.readAllBytes(fixtureBin)))
        assertEquals(fromBin.name, fromB64.name)
        assertEquals(fromBin.columns, fromB64.columns)
        assertEquals(fromBin.rows.size, fromB64.rows.size)
    }
}
