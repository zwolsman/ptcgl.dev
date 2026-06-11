package com.zwolsman.ptcgl.unity.bundle

import com.zwolsman.ptcgl.unity.io.EndianBinaryReader
import com.zwolsman.ptcgl.unity.lz4.Lz4

private const val SIGNATURE = "UnityFS"

// Flags bits
private const val FLAG_COMPRESSION_MASK = 0x3F
private const val FLAG_BLOCK_INFO_AT_END = 0x40

// Compression types
private const val COMPRESSION_NONE  = 0
private const val COMPRESSION_LZ4   = 2
private const val COMPRESSION_LZ4HC = 3

data class BundleFile(val path: String, val data: ByteArray)

/**
 * Parser for the UnityFS container format used by PTCGL asset bundles.
 *
 * Layout:
 *   Header → compressed blocks info → concatenated (possibly compressed) data blocks
 *   Directory entries within blocks info describe embedded files by offset + size.
 */
object UnityBundle {

    fun parse(bytes: ByteArray): List<BundleFile> {
        val r = EndianBinaryReader(bytes)

        val sig = r.readCString()
        require(sig == SIGNATURE) { "Not a UnityFS file; got signature: '$sig'" }

        val version = r.readInt32()     // format version (unused beyond sanity)
        r.readCString()                 // unity version string
        r.readCString()                 // unity revision string
        val bundleSize = r.readInt64()  // total file size
        val compressedBlocksInfoSize = r.readInt32()
        val uncompressedBlocksInfoSize = r.readInt32()
        val flags = r.readUInt32().toInt()

        require(bundleSize > 0) { "Invalid bundle size: $bundleSize" }

        // Blocks info can be at the end of the file or immediately after the header
        val blocksInfoBytes = if ((flags and FLAG_BLOCK_INFO_AT_END) != 0) {
            val offset = (bundleSize - compressedBlocksInfoSize).toInt()
            EndianBinaryReader(bytes, offset).readBytes(compressedBlocksInfoSize)
        } else {
            r.readBytes(compressedBlocksInfoSize)
        }

        val rawBlocksInfo = decompress(blocksInfoBytes, flags and FLAG_COMPRESSION_MASK, uncompressedBlocksInfoSize)
        val bi = EndianBinaryReader(rawBlocksInfo)

        // 16-byte uncompressed data hash (ignored)
        bi.skip(16)

        val storageBlocks = Array(bi.readInt32()) {
            Triple(
                bi.readUInt32().toInt(),   // uncompressed size
                bi.readUInt32().toInt(),   // compressed size
                bi.readUInt16()            // flags (bits 0-5 = compression)
            )
        }

        val dirEntries = Array(bi.readInt32()) {
            val offset = bi.readInt64()
            val size   = bi.readInt64()
            val dFlags = bi.readUInt32()
            val path   = bi.readCString()
            Triple(offset, size, path)
        }

        // Decompress storage blocks into a single virtual data buffer
        val dataChunks = mutableListOf<ByteArray>()
        var dataSize = 0L
        for ((uncompSize, compSize, blockFlags) in storageBlocks) {
            val compType = blockFlags and FLAG_COMPRESSION_MASK
            val chunk = r.readBytes(compSize)
            val raw = if (compType == COMPRESSION_NONE) chunk
                      else decompress(chunk, compType, uncompSize)
            dataChunks += raw
            dataSize += uncompSize
        }

        val data = ByteArray(dataSize.toInt())
        var off = 0
        for (chunk in dataChunks) { chunk.copyInto(data, off); off += chunk.size }

        return dirEntries.map { (offset, size, path) ->
            BundleFile(path, data.copyOfRange(offset.toInt(), (offset + size).toInt()))
        }
    }

    private fun decompress(src: ByteArray, compressionType: Int, uncompressedSize: Int): ByteArray =
        when (compressionType) {
            COMPRESSION_NONE        -> src
            COMPRESSION_LZ4,
            COMPRESSION_LZ4HC      -> Lz4.decompress(src, uncompressedSize)
            else -> error("Unsupported compression type: $compressionType")
        }
}
