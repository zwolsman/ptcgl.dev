package com.zwolsman.ptcgl.unity.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Big-endian binary reader for Unity formats (UnityFS, SerializedFile header).
 *
 * Null-terminated strings are read byte-by-byte until 0x00; the terminator is consumed.
 * All multi-byte integers are big-endian.
 */
class EndianBinaryReader(private val buf: ByteArray, startPos: Int = 0) {

    var pos: Int = startPos
        private set

    val remaining: Int get() = buf.size - pos
    val size: Int get() = buf.size

    fun readByte(): Byte = buf[pos++]

    fun readUByte(): Int = buf[pos++].toInt() and 0xFF

    fun readBoolean(): Boolean = readUByte() != 0

    fun readInt16(): Short {
        val v = ByteBuffer.wrap(buf, pos, 2).order(ByteOrder.BIG_ENDIAN).short
        pos += 2
        return v
    }

    fun readUInt16(): Int = readInt16().toInt() and 0xFFFF

    fun readInt32(): Int {
        val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.BIG_ENDIAN).int
        pos += 4
        return v
    }

    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    fun readInt64(): Long {
        val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.BIG_ENDIAN).long
        pos += 8
        return v
    }

    /** Read a null-terminated (C-style) string. The null terminator is consumed. */
    fun readCString(): String {
        val start = pos
        while (pos < buf.size && buf[pos] != 0.toByte()) pos++
        val s = String(buf, start, pos - start, Charsets.UTF_8)
        if (pos < buf.size) pos++ // consume the null terminator
        return s
    }

    fun readBytes(count: Int): ByteArray {
        val b = buf.copyOfRange(pos, pos + count)
        pos += count
        return b
    }

    fun skip(count: Int) {
        pos += count
    }

    fun seekTo(position: Int) {
        pos = position
    }

    /** Align the read position to a multiple of [alignment] bytes (from pos 0). */
    fun align(alignment: Int) {
        val rem = pos % alignment
        if (rem != 0) pos += alignment - rem
    }

    /** Return a new reader backed by the same buffer starting at [offset] with the given [length]. */
    fun slice(offset: Int, length: Int): EndianBinaryReader =
        EndianBinaryReader(buf.copyOfRange(offset, offset + length))
}
