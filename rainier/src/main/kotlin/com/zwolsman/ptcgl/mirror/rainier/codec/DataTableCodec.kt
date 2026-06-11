package com.zwolsman.ptcgl.mirror.rainier.codec

import java.util.Base64

/**
 * Decodes the .NET DataTable binary format produced by the Pokémon card database API.
 *
 * Wire format:
 *   1. base64-decode the contentString
 *   2. QuickLZ level-1 decompress
 *   3. Parse the little-endian .NET DataTable binary (this class)
 */
object DataTableCodec {

    data class Column(val name: String, val typeName: String)

    data class Table(
        val name: String,
        val columns: List<Column>,
        val rows: List<Map<String, Any?>>,
    )

    /**
     * Full pipeline: base64 → (optional QuickLZ) → DataTable.
     *
     * The binary payload has a 1-byte prefix from BufferedRealtimeCompressionEngine:
     *   0x00 = raw (not compressed): DataTable follows immediately at offset 1
     *   other = QuickLZ compressed: QuickLZ header + data follows at offset 1
     *
     * This matches empirical observation: the first byte is 0x00 for card DB docs
     * and the DataTable binary (TableName, colCount, columns, rowCount, rows) begins at offset 1.
     */
    fun decodeFromBase64(payloadBase64: String): Table {
        val raw = Base64.getDecoder().decode(payloadBase64)
        require(raw.isNotEmpty()) { "Empty payload" }
        val engineByte = raw[0].toInt() and 0xFF
        val dataBytes = if (engineByte == 0x00) {
            // not compressed — DataTable starts at offset 1
            raw.copyOfRange(1, raw.size)
        } else {
            // QuickLZ compressed — skip engine byte, then decompress
            QuickLz.decompress(raw.copyOfRange(1, raw.size))
        }
        return parse(DotNetBinaryReader(dataBytes))
    }

    fun parse(reader: DotNetBinaryReader): Table {
        val tableName = reader.readString()
        val colCount = reader.readInt32()
        val columns = (0 until colCount).map {
            Column(name = reader.readString(), typeName = reader.readString())
        }
        val rowCount = reader.readInt32()
        val rows = (0 until rowCount).map {
            columns.associate { col -> col.name to readCell(reader, col.typeName) }
        }
        return Table(tableName, columns, rows)
    }

    private fun readCell(reader: DotNetBinaryReader, typeName: String): Any? =
        when (val marker = reader.readUByte()) {
            0 -> readValue(reader, typeName)
            1, 2 -> null  // DBNull or null
            else -> error("Unknown cell marker $marker for column type $typeName")
        }

    private fun readValue(reader: DotNetBinaryReader, typeName: String): Any? =
        when (typeName) {
            "Boolean",   "System.Boolean"  -> reader.readBoolean()
            "Byte",      "System.Byte",
            "CardCategory"                 -> reader.readUByte()
            "SByte",     "System.SByte"   -> reader.readByte().toInt()
            "Int16",     "System.Int16"   -> reader.readInt16()
            "UInt16",    "System.UInt16"  -> reader.readUInt16()
            "Int32",     "System.Int32"   -> reader.readInt32()
            "UInt32",    "System.UInt32"  -> reader.readUInt32()
            "Int64",     "System.Int64"   -> reader.readInt64()
            "UInt64",    "System.UInt64"  -> reader.readUInt64()
            "Single",    "System.Single"  -> reader.readSingle()
            "Double",    "System.Double"  -> reader.readDouble()
            "Char",      "System.Char"    -> reader.readUInt16().toChar()
            "String",    "System.String"  -> reader.readString()
            "Byte[]"                      -> reader.readBytes(reader.readInt32())
            "DateTime",  "System.DateTime"-> reader.readInt64()  // ticks since 0001-01-01
            "Decimal",   "System.Decimal" -> reader.readDecimal()
            "Guid",      "System.Guid"    -> reader.readGuid()
            "TimeSpan",  "System.TimeSpan"-> reader.readInt64()  // ticks
            else -> error("Unknown DataTable column type: '$typeName' — add handling for this type")
        }
}
