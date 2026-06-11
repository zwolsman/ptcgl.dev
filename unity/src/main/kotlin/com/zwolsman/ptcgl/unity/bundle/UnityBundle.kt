package com.zwolsman.ptcgl.unity.bundle

import com.zwolsman.ptcgl.unity.io.EndianBinaryReader
import com.zwolsman.ptcgl.unity.lz4.Lz4
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream

private const val SIGNATURE = "UnityFS"

// Compression type in bits 0-5 of the flags field
private const val FLAG_COMPRESSION_MASK   = 0x3F
// Flags in the bundle header
private const val FLAG_BLOCK_INFO_AT_END  = 0x80   // kBlocksInfoAtTheEnd
private const val FLAG_BLOCK_INFO_PADDING = 0x200  // kBlockInfoNeedPaddingAtStart — 16-byte align

// Block-level compression types
private const val COMPRESSION_NONE  = 0
private const val COMPRESSION_LZMA  = 1
private const val COMPRESSION_LZ4   = 2
private const val COMPRESSION_LZ4HC = 3

data class BundleFile(val path: String, val data: ByteArray)

/**
 * Parser for the UnityFS container format used by PTCGL asset bundles.
 *
 * Layout (flags = 0x243 for typical PTCGL bundles):
 *   paddingAtStart=true, blocksInfoAtEnd=false
 *
 *   Header → (align 16) → compressed blocks info
 *          → (align 16) → concatenated compressed data blocks
 */
object UnityBundle {

    fun parse(bytes: ByteArray): List<BundleFile> {
        val r = EndianBinaryReader(bytes)

        val sig = r.readCString()
        require(sig == SIGNATURE) { "Not a UnityFS file; got signature: '$sig'" }

        r.readInt32()              // version (format version, unused)
        r.readCString()            // unity version string (e.g. "5.x.x")
        r.readCString()            // unity revision (e.g. "6000.3.5f2")

        val bundleSize = r.readInt64()
        val compressedBlocksInfoSize = r.readInt32()
        val uncompressedBlocksInfoSize = r.readInt32()
        val flags = r.readUInt32().toInt()

        val blocksInfoCompression = flags and FLAG_COMPRESSION_MASK
        val blocksInfoAtEnd = (flags and FLAG_BLOCK_INFO_AT_END) != 0
        val paddingAtStart  = (flags and FLAG_BLOCK_INFO_PADDING) != 0

        require(bundleSize > 0) { "Invalid bundle size: $bundleSize" }

        // Read compressed blocks info
        val blocksInfoBytes = if (blocksInfoAtEnd) {
            val offset = (bundleSize - compressedBlocksInfoSize).toInt()
            EndianBinaryReader(bytes, offset).readBytes(compressedBlocksInfoSize)
        } else {
            if (paddingAtStart) r.align(16)
            r.readBytes(compressedBlocksInfoSize)
        }

        val rawBlocksInfo = decompress(blocksInfoBytes, blocksInfoCompression, uncompressedBlocksInfoSize)
        val bi = EndianBinaryReader(rawBlocksInfo)

        bi.skip(16) // 16-byte data hash (ignored)

        val storageBlocks = Array(bi.readInt32()) {
            Triple(
                bi.readInt32(),    // uncompressed size
                bi.readInt32(),    // compressed size
                bi.readUInt16()    // flags (bits 0-5 = compression type)
            )
        }

        val dirEntries = Array(bi.readInt32()) {
            val offset = bi.readInt64()
            val size   = bi.readInt64()
            bi.readUInt32()        // dirEntry flags (unused)
            val path   = bi.readCString()
            Triple(offset, size, path)
        }

        // Before data blocks, apply padding again if required
        if (!blocksInfoAtEnd && paddingAtStart) r.align(16)

        // Decompress storage blocks into a single virtual data buffer
        val dataChunks = mutableListOf<ByteArray>()
        var dataSize = 0
        for ((uncompSize, compSize, blockFlags) in storageBlocks) {
            val compType = blockFlags and FLAG_COMPRESSION_MASK
            val chunk = r.readBytes(compSize)
            val raw = decompress(chunk, compType, uncompSize)
            dataChunks += raw
            dataSize += uncompSize
        }

        val data = ByteArray(dataSize)
        var off = 0
        for (chunk in dataChunks) { chunk.copyInto(data, off); off += chunk.size }

        return dirEntries.map { (offset, size, path) ->
            BundleFile(path, data.copyOfRange(offset.toInt(), (offset + size).toInt()))
        }
    }

    private fun decompress(src: ByteArray, compressionType: Int, uncompressedSize: Int): ByteArray =
        when (compressionType) {
            COMPRESSION_NONE        -> src
            COMPRESSION_LZMA        -> decompressLzma(src, uncompressedSize)
            COMPRESSION_LZ4,
            COMPRESSION_LZ4HC      -> Lz4.decompress(src, uncompressedSize)
            else -> error("Unsupported compression type: $compressionType")
        }

    // Unity LZMA layout: propsByte (1B) + dictSize (4B LE) + raw LZMA stream.
    // Prepend a standard 13-byte LZMA file header so LZMAInputStream can parse it.
    private fun decompressLzma(src: ByteArray, uncompressedSize: Int): ByteArray {
        val header = ByteArray(13)
        header[0] = src[0]
        header[1] = src[1]
        header[2] = src[2]
        header[3] = src[3]
        header[4] = src[4]
        var sz = uncompressedSize.toLong()
        for (i in 0 until 8) { header[5 + i] = (sz and 0xFF).toByte(); sz = sz ushr 8 }
        val stream = SequenceInputStream(ByteArrayInputStream(header), ByteArrayInputStream(src, 5, src.size - 5))
        return LZMAInputStream(stream).readBytes()
    }
}
