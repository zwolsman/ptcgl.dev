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

    /** Full pipeline: base64 → QuickLZ → DataTable. */
    fun decodeFromBase64(contentString: String): Table {
        val compressed = Base64.getDecoder().decode(contentString)
        val raw = QuickLz.decompress(compressed)
        return parse(DotNetBinaryReader(raw))
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
            "Boolean"              -> reader.readBoolean()
            "Byte", "CardCategory" -> reader.readUByte()
            "SByte"               -> reader.readByte().toInt()
            "Int16"               -> reader.readInt16()
            "UInt16"              -> reader.readUInt16()
            "Int32"               -> reader.readInt32()
            "UInt32"              -> reader.readUInt32()
            "Int64"               -> reader.readInt64()
            "UInt64"              -> reader.readUInt64()
            "Single"              -> reader.readSingle()
            "Double"              -> reader.readDouble()
            "Char"                -> reader.readUInt16().toChar()
            "String"              -> reader.readString()
            "Byte[]"              -> reader.readBytes(reader.readInt32())
            // .NET DateTime: Int64 ticks (100 ns since 0001-01-01)
            "DateTime"            -> reader.readInt64()
            "Decimal"             -> reader.readDecimal()
            "Guid"                -> reader.readGuid()
            // .NET TimeSpan: Int64 ticks
            "TimeSpan"            -> reader.readInt64()
            else -> error("Unknown DataTable column type: '$typeName' — add handling for this type")
        }
}
