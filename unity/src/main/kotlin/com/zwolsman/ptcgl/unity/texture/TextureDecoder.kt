package com.zwolsman.ptcgl.unity.texture

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Decodes Unity Texture2D raw bytes into PNG.
 *
 * Supported Unity TextureFormat values:
 *   4  = RGBA32  (uncompressed, 4 bytes/pixel)
 *  10  = DXT1    (BC1, opaque or 1-bit alpha)
 *  12  = DXT5    (BC3, full alpha)
 *
 * Returns null for unsupported formats so the caller can decide what to do.
 */
object TextureDecoder {

    private const val FORMAT_RGBA32 = 4
    private const val FORMAT_DXT1   = 10
    private const val FORMAT_DXT5   = 12

    fun toPng(data: ByteArray, width: Int, height: Int, format: Int): ByteArray? {
        val pixels = when (format) {
            FORMAT_RGBA32 -> decodeRgba32(data, width, height)
            FORMAT_DXT1   -> decodeDxt1(data, width, height)
            FORMAT_DXT5   -> decodeDxt5(data, width, height)
            else          -> return null
        }
        return encodePng(pixels, width, height)
    }

    // --- RGBA32 ---

    private fun decodeRgba32(data: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = data[i * 4 + 0].u8()
            val g = data[i * 4 + 1].u8()
            val b = data[i * 4 + 2].u8()
            val a = data[i * 4 + 3].u8()
            pixels[i] = argb(a, r, g, b)
        }
        return pixels
    }

    // --- DXT1 (BC1) ---

    private fun decodeDxt1(data: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height) { argb(255, 0, 0, 0) }
        val blocksX = (width  + 3) / 4
        val blocksY = (height + 3) / 4
        var src = 0

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val c0 = u16le(data, src)
                val c1 = u16le(data, src + 2)
                val bits = u32le(data, src + 4)
                src += 8

                val palette = colorPalette(c0, c1, punchThrough = c0 <= c1)

                for (py in 0 until 4) {
                    for (px in 0 until 4) {
                        val x = bx * 4 + px; val y = by * 4 + py
                        if (x >= width || y >= height) continue
                        pixels[y * width + x] = palette[(bits ushr ((py * 4 + px) * 2)) and 3]
                    }
                }
            }
        }
        return pixels
    }

    // --- DXT5 (BC3) ---

    private fun decodeDxt5(data: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val blocksX = (width  + 3) / 4
        val blocksY = (height + 3) / 4
        var src = 0

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                // Alpha block — 8 bytes
                val a0 = data[src + 0].u8()
                val a1 = data[src + 1].u8()
                val abits = u48le(data, src + 2)   // 48 bits of 3-bit indices
                src += 8

                // Color block — 8 bytes
                val c0 = u16le(data, src)
                val c1 = u16le(data, src + 2)
                val cbits = u32le(data, src + 4)
                src += 8

                val alphaPal = alphaPalette(a0, a1)
                val colorPal = colorPalette(c0, c1, punchThrough = false)

                for (py in 0 until 4) {
                    for (px in 0 until 4) {
                        val x = bx * 4 + px; val y = by * 4 + py
                        if (x >= width || y >= height) continue
                        val pi = py * 4 + px
                        val ai = ((abits ushr (pi * 3)) and 7L).toInt()
                        val ci = (cbits ushr (pi * 2)) and 3
                        pixels[y * width + x] = (alphaPal[ai] shl 24) or (colorPal[ci] and 0x00FFFFFF)
                    }
                }
            }
        }
        return pixels
    }

    // --- Palette builders ---

    private fun alphaPalette(a0: Int, a1: Int): IntArray {
        val p = IntArray(8)
        p[0] = a0; p[1] = a1
        if (a0 > a1) {
            p[2] = (6 * a0 + 1 * a1) / 7
            p[3] = (5 * a0 + 2 * a1) / 7
            p[4] = (4 * a0 + 3 * a1) / 7
            p[5] = (3 * a0 + 4 * a1) / 7
            p[6] = (2 * a0 + 5 * a1) / 7
            p[7] = (1 * a0 + 6 * a1) / 7
        } else {
            p[2] = (4 * a0 + 1 * a1) / 5
            p[3] = (3 * a0 + 2 * a1) / 5
            p[4] = (2 * a0 + 3 * a1) / 5
            p[5] = (1 * a0 + 4 * a1) / 5
            p[6] = 0
            p[7] = 255
        }
        return p
    }

    private fun colorPalette(c0: Int, c1: Int, punchThrough: Boolean): IntArray {
        val r0 = exp5(c0 shr 11); val g0 = exp6((c0 shr 5) and 0x3F); val b0 = exp5(c0 and 0x1F)
        val r1 = exp5(c1 shr 11); val g1 = exp6((c1 shr 5) and 0x3F); val b1 = exp5(c1 and 0x1F)
        val p = IntArray(4)
        p[0] = argb(255, r0, g0, b0)
        p[1] = argb(255, r1, g1, b1)
        if (!punchThrough) {
            p[2] = argb(255, (2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3)
            p[3] = argb(255, (r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3)
        } else {
            p[2] = argb(255, (r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2)
            p[3] = argb(0, 0, 0, 0)
        }
        return p
    }

    // --- Bit / byte helpers ---

    private fun Byte.u8() = toInt() and 0xFF
    private fun exp5(v: Int) = (v and 0x1F).let { (it shl 3) or (it shr 2) }
    private fun exp6(v: Int) = (v and 0x3F).let { (it shl 2) or (it shr 4) }
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun u16le(b: ByteArray, o: Int) =
        (b[o].u8()) or (b[o + 1].u8() shl 8)

    private fun u32le(b: ByteArray, o: Int) =
        (b[o].u8()) or (b[o + 1].u8() shl 8) or (b[o + 2].u8() shl 16) or (b[o + 3].u8() shl 24)

    /** Read 6 bytes as a 48-bit little-endian value into a Long. */
    private fun u48le(b: ByteArray, o: Int): Long =
        (b[o + 0].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24) or
        ((b[o + 4].toLong() and 0xFF) shl 32) or
        ((b[o + 5].toLong() and 0xFF) shl 40)

    // --- PNG encoder ---

    private fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
    }
}
