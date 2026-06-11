package com.zwolsman.ptcgl.mirror.rainier.codec

/**
 * QuickLZ 1.5.1 level-1 decompressor.
 *
 * Header layout (9 bytes, 32-bit sizes):
 *   byte 0 : flags  — bit 1 = compressed, bits 4-6 = level (must be 1)
 *   bytes 1-4 : compressed size   (uint32 LE, includes the 9-byte header)
 *   bytes 5-8 : uncompressed size (uint32 LE)
 *
 * Back-reference encoding (level 1, 2-or-3-byte):
 *   uint16 LE fetch from source
 *   low  4 bits = matchlen - 2  (value 0 signals a 3-byte long match)
 *   high 12 bits = backward byte offset in the output buffer
 *   Long match: low nibble == 0, then read one more byte: matchlen = byte + 18
 */
object QuickLz {

    fun decompress(source: ByteArray): ByteArray {
        require(source.size >= 9) { "Source too small for QuickLZ header" }

        val flags = source[0].toInt() and 0xFF
        val isCompressed = (flags and 0x2) != 0
        val level = (flags ushr 4) and 0xF
        require(level == 1) { "Only QuickLZ level 1 is supported, got level $level" }

        val uncompressedSize = readInt32LE(source, 5)
        if (!isCompressed) {
            return source.copyOfRange(9, 9 + uncompressedSize)
        }

        val out = ByteArray(uncompressedSize)
        var src = 9
        var dst = 0

        while (dst < uncompressedSize - 1) {
            val control = readInt32LE(source, src)
            src += 4

            var bit = 1
            while (bit != 0 && dst < uncompressedSize - 1) {
                if (control and bit == 0) {
                    out[dst++] = source[src++]
                } else {
                    val lo = source[src].toInt() and 0xFF
                    val hi = source[src + 1].toInt() and 0xFF
                    val fetch = lo or (hi shl 8)
                    val nibble = fetch and 0xF
                    val offset = fetch ushr 4

                    val matchLen: Int
                    if (nibble == 0) {
                        matchLen = (source[src + 2].toInt() and 0xFF) + 18
                        src += 3
                    } else {
                        matchLen = nibble + 2
                        src += 2
                    }

                    var from = dst - offset
                    repeat(matchLen) { out[dst++] = out[from++] }
                }
                bit = bit shl 1
            }
        }

        // trailing literal bytes (UNCOMPRESSED_END region)
        while (dst < uncompressedSize) {
            out[dst++] = source[src++]
        }

        return out
    }

    private fun readInt32LE(buf: ByteArray, pos: Int): Int =
        (buf[pos].toInt() and 0xFF) or
        ((buf[pos + 1].toInt() and 0xFF) shl 8) or
        ((buf[pos + 2].toInt() and 0xFF) shl 16) or
        ((buf[pos + 3].toInt() and 0xFF) shl 24)
}
