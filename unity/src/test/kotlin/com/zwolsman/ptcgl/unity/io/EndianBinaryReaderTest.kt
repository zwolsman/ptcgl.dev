package com.zwolsman.ptcgl.unity.io

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EndianBinaryReaderTest {

    @Test
    fun `readInt32 is big-endian`() {
        val r = EndianBinaryReader(byteArrayOf(0x00, 0x00, 0x01, 0x00))
        assertEquals(256, r.readInt32())
    }

    @Test
    fun `readUInt32 treats high bit as magnitude`() {
        val r = EndianBinaryReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(0xFFFFFFFFL, r.readUInt32())
    }

    @Test
    fun `readInt64 is big-endian`() {
        val r = EndianBinaryReader(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        assertEquals(1L, r.readInt64())
    }

    @Test
    fun `readCString reads up to null terminator`() {
        val r = EndianBinaryReader(byteArrayOf(0x41, 0x42, 0x43, 0x00, 0x44))
        assertEquals("ABC", r.readCString())
        assertEquals(4, r.pos)
    }

    @Test
    fun `align advances pos to next multiple`() {
        val r = EndianBinaryReader(ByteArray(16))
        r.skip(3)
        r.align(4)
        assertEquals(4, r.pos)
    }

    @Test
    fun `sequential reads advance pos`() {
        val r = EndianBinaryReader(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        assertEquals(1, r.readInt32() shr 24)
        assertEquals(2, r.readInt32() shr 24)
    }
}
