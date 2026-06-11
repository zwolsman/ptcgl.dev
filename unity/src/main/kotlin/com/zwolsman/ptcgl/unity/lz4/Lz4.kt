package com.zwolsman.ptcgl.unity.lz4

import net.jpountz.lz4.LZ4Factory

private val factory = LZ4Factory.fastestInstance()

object Lz4 {
    /** Decompress LZ4 block data (not LZ4 frame format, as used by Unity). */
    fun decompress(src: ByteArray, uncompressedSize: Int): ByteArray {
        val dest = ByteArray(uncompressedSize)
        factory.fastDecompressor().decompress(src, 0, dest, 0, uncompressedSize)
        return dest
    }
}
