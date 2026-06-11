package com.zwolsman.ptcgl.unity.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary reader that supports both big-endian and little-endian reads.
 *
 * [bigEndian] defaults to true for UnityFS headers (which are big-endian).
 * SerializedFile metadata is little-endian — use [withBigEndian](false) after parsing the header.
 */
class EndianBinaryReader(private val buf: ByteArray, startPos: Int = 0, val bigEndian: Boolean = true) {

    var pos: Int = startPos
        private set

    val remaining: Int get() = buf.size - pos
    val size: Int get() = buf.size

    private fun order(): ByteOrder = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

    fun readByte(): Byte = buf[pos++]

    fun readUByte(): Int = buf[pos++].toInt() and 0xFF

    fun readBoolean(): Boolean = readUByte() != 0

    fun readInt16(): Short {
        val v = ByteBuffer.wrap(buf, pos, 2).order(order()).short
        pos += 2
        return v
    }

    fun readUInt16(): Int = readInt16().toInt() and 0xFFFF

    fun readInt32(): Int {
        val v = ByteBuffer.wrap(buf, pos, 4).order(order()).int
        pos += 4
        return v
    }

    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    fun readInt64(): Long {
        val v = ByteBuffer.wrap(buf, pos, 8).order(order()).long
        pos += 8
        return v
    }

    /** Read a null-terminated (C-style) string. The null terminator is consumed. */
    fun readCString(): String {
        val start = pos
        while (pos < buf.size && buf[pos] != 0.toByte()) pos++
        val s = String(buf, start, pos - start, Charsets.UTF_8)
        if (pos < buf.size) pos++
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

    /** Return a new reader backed by the same buffer at the current position with different endianness. */
    fun withBigEndian(be: Boolean): EndianBinaryReader = EndianBinaryReader(buf, pos, be)

    /** Return a new reader backed by the same buffer starting at [offset]. */
    fun slice(offset: Int, length: Int): EndianBinaryReader =
        EndianBinaryReader(buf.copyOfRange(offset, offset + length), 0, bigEndian)
}
