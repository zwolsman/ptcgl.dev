package com.zwolsman.ptcgl.mirror.rainier.codec

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Little-endian binary reader matching .NET BinaryReader semantics.
 * Strings are 7-bit LEB128 byte-length prefix + UTF-8 bytes.
 */
class DotNetBinaryReader(private val buf: ByteArray, startPos: Int = 0) {

    var pos: Int = startPos
        private set

    val remaining: Int get() = buf.size - pos

    fun readBoolean(): Boolean = readByte() != 0.toByte()

    fun readByte(): Byte = buf[pos++]

    fun readUByte(): Int = buf[pos++].toInt() and 0xFF

    fun readInt16(): Short {
        val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short
        pos += 2
        return v
    }

    fun readUInt16(): Int = readInt16().toInt() and 0xFFFF

    fun readInt32(): Int {
        val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
        pos += 4
        return v
    }

    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    fun readInt64(): Long {
        val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
        pos += 8
        return v
    }

    fun readUInt64(): Long = readInt64()

    fun readSingle(): Float {
        val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
        pos += 4
        return v
    }

    fun readDouble(): Double {
        val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double
        pos += 8
        return v
    }

    fun readString(): String {
        val byteLen = readLEB128()
        val s = String(buf, pos, byteLen, Charsets.UTF_8)
        pos += byteLen
        return s
    }

    fun readBytes(count: Int): ByteArray {
        val b = buf.copyOfRange(pos, pos + count)
        pos += count
        return b
    }

    /** .NET Decimal: lo, mid, hi, flags each as Int32 LE. */
    fun readDecimal(): BigDecimal {
        val lo = readInt32()
        val mid = readInt32()
        val hi = readInt32()
        val flags = readInt32()
        val scale = (flags ushr 16) and 0xFF
        val isNeg = (flags and Int.MIN_VALUE) != 0
        val magnitude = BigInteger.valueOf(hi.toLong() and 0xFFFFFFFFL)
            .shiftLeft(32)
            .or(BigInteger.valueOf(mid.toLong() and 0xFFFFFFFFL))
            .shiftLeft(32)
            .or(BigInteger.valueOf(lo.toLong() and 0xFFFFFFFFL))
        val bd = BigDecimal(magnitude, scale)
        return if (isNeg) bd.negate() else bd
    }

    /**
     * .NET GUID layout: first three components are little-endian, last 8 bytes are big-endian.
     * Returns the standard 8-4-4-4-12 hex string.
     */
    fun readGuid(): String {
        val a = readInt32()
        val b = readInt16().toInt() and 0xFFFF
        val c = readInt16().toInt() and 0xFFFF
        val d = readBytes(8)
        return "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x".format(
            a, b, c,
            d[0].toInt() and 0xFF, d[1].toInt() and 0xFF,
            d[2].toInt() and 0xFF, d[3].toInt() and 0xFF,
            d[4].toInt() and 0xFF, d[5].toInt() and 0xFF,
            d[6].toInt() and 0xFF, d[7].toInt() and 0xFF
        )
    }

    private fun readLEB128(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = readUByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}
