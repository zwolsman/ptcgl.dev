package com.zwolsman.ptcgl.mirror

import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.codec.DotNetBinaryReader
import com.zwolsman.ptcgl.mirror.rainier.codec.QuickLz
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardDbCodecTest {

    private val fixtureBin = Path.of("src/test/resources/fixtures/card-db-sv1-en.bin")
    private val fixtureB64 = Path.of("src/test/resources/fixtures/card-db-sv1-en.b64")

    @Test
    fun `QuickLz round-trip produces expected uncompressed size`() {
        if (Files.notExists(fixtureB64)) return // fixture not yet generated — run VerifyCardDbRunner first
        val compressed = java.util.Base64.getDecoder().decode(Files.readString(fixtureB64))
        val decompressed = QuickLz.decompress(compressed)
        assertTrue(decompressed.isNotEmpty(), "Decompressed output should not be empty")
        // The decompressed size is encoded in the QuickLZ header bytes 5-8
        val expectedSize = (compressed[5].toInt() and 0xFF) or
                ((compressed[6].toInt() and 0xFF) shl 8) or
                ((compressed[7].toInt() and 0xFF) shl 16) or
                ((compressed[8].toInt() and 0xFF) shl 24)
        assertEquals(expectedSize, decompressed.size)
    }

    @Test
    fun `DataTable parses column names and row count from fixture`() {
        if (Files.notExists(fixtureBin)) return // fixture not yet generated
        val bytes = Files.readAllBytes(fixtureBin)
        val table = DataTableCodec.parse(DotNetBinaryReader(bytes))
        println("Table: ${table.name}")
        println("Columns (${table.columns.size}):")
        table.columns.forEach { println("  ${it.name.padEnd(40)} ${it.typeName}") }
        println("Rows: ${table.rows.size}")
        println("First row: ${table.rows.firstOrNull()}")
        assertTrue(table.columns.isNotEmpty(), "Table must have columns")
        assertTrue(table.rows.isNotEmpty(), "Table must have rows")
    }

    @Test
    fun `DataTable contains expected card columns`() {
        if (Files.notExists(fixtureBin)) return
        val bytes = Files.readAllBytes(fixtureBin)
        val table = DataTableCodec.parse(DotNetBinaryReader(bytes))
        val colNames = table.columns.map { it.name }.toSet()
        // Verify columns mentioned in the DESIGN.md are present
        val expected = setOf("cardID", "EN Card Name", "EN Card #")
        val missing = expected - colNames
        assertTrue(missing.isEmpty(), "Missing expected columns: $missing. Got: $colNames")
    }
}
