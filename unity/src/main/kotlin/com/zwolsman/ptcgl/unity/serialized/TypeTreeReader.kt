package com.zwolsman.ptcgl.unity.serialized

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val META_FLAG_ALIGN = 0x4000

/**
 * Reads a single Unity object by walking its TypeTree depth-first.
 *
 * All object data is little-endian (the serialized file's object section is always LE
 * in practice, even when the header declares big-endian — this matches Unity behaviour).
 *
 * Returns a nested structure of:
 *   - Map<String, Any?> for structs / MonoBehaviour roots
 *   - List<Any?>        for arrays / vectors
 *   - Primitives        (Int, Long, Float, Double, Boolean, String, ByteArray)
 */
object TypeTreeReader {

    fun read(file: SerializedFile, obj: ObjectInfo): Map<String, Any?> {
        val buf = ByteBuffer.wrap(file.data, obj.byteStart.toInt(), obj.byteSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        val type = file.types[obj.typeIndex]
        val nodes = type.nodes
        require(nodes.isNotEmpty()) { "Type at index ${obj.typeIndex} has no TypeTree nodes" }

        val result = readStruct(buf, nodes, startIdx = 0, depth = nodes[0].depth)
        return result as? Map<String, Any?> ?: mapOf("value" to result)
    }

    /**
     * Read a struct node and its children. Returns Map<String, Any?>.
     * [startIdx] is the index of the struct node itself; children follow with depth = structDepth+1.
     */
    private fun readStruct(buf: ByteBuffer, nodes: List<TypeTreeNode>, startIdx: Int, depth: Int): Any? {
        val node = nodes[startIdx]

        if (node.isArray) {
            return readArray(buf, nodes, startIdx)
        }

        // Determine if this is a leaf (no children at depth+1 following it)
        val nextIdx = startIdx + 1
        val hasChildren = nextIdx < nodes.size && nodes[nextIdx].depth > depth

        if (!hasChildren) {
            return readPrimitive(buf, node).also {
                if (node.metaFlags and META_FLAG_ALIGN != 0) alignBuffer(buf)
            }
        }

        // Struct: collect children
        val map = mutableMapOf<String, Any?>()
        var i = nextIdx
        while (i < nodes.size && nodes[i].depth > depth) {
            val child = nodes[i]
            if (child.depth == depth + 1) {
                map[child.fieldName] = readStruct(buf, nodes, i, child.depth)
            }
            i++
        }
        if (node.metaFlags and META_FLAG_ALIGN != 0) alignBuffer(buf)
        return map
    }

    /**
     * Read an Array node. The Array node has two children:
     *   [0] size  (int)
     *   [1] data  (element type)
     */
    private fun readArray(buf: ByteBuffer, nodes: List<TypeTreeNode>, arrayIdx: Int): List<Any?> {
        val arrayDepth = nodes[arrayIdx].depth
        // Children: depth = arrayDepth + 1
        val children = nodes.drop(arrayIdx + 1).takeWhile { it.depth > arrayDepth }
        // First child = size, second = data element type
        val sizeNode = children.first()
        val elemNodes = nodes.drop(arrayIdx + 2) // elements follow the size node

        val count = buf.int // size is always int32
        if (sizeNode.metaFlags and META_FLAG_ALIGN != 0) alignBuffer(buf)

        // Find the element node (depth = arrayDepth + 1, after sizeNode)
        val elemNodeIdx = arrayIdx + 2
        if (elemNodeIdx >= nodes.size) return emptyList()
        val elemNode = nodes[elemNodeIdx]

        return List(count) {
            readStruct(buf, nodes, elemNodeIdx, elemNode.depth)
        }.also {
            if (nodes[arrayIdx].metaFlags and META_FLAG_ALIGN != 0) alignBuffer(buf)
        }
    }

    private fun readPrimitive(buf: ByteBuffer, node: TypeTreeNode): Any? =
        when (node.typeName) {
            "bool"                              -> buf.get() != 0.toByte()
            "SInt8"                             -> buf.get().toInt()
            "UInt8", "char"                     -> buf.get().toInt() and 0xFF
            "SInt16", "short"                   -> buf.short.toInt()
            "UInt16", "unsigned short"          -> buf.short.toInt() and 0xFFFF
            "SInt32", "int"                     -> buf.int
            "UInt32", "unsigned int"            -> buf.int.toLong() and 0xFFFFFFFFL
            "SInt64", "long long"               -> buf.long
            "UInt64", "unsigned long long"      -> buf.long
            "float"                             -> buf.float
            "double"                            -> buf.double
            "string"                            -> readString(buf)
            "TypelessData"                      -> { val len = buf.int; ByteArray(len).also { buf.get(it) } }
            else                                -> {
                // Fixed-size unknown type — read raw bytes
                if (node.size > 0) ByteArray(node.size).also { buf.get(it) }
                else null
            }
        }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.int
        val bytes = ByteArray(len)
        buf.get(bytes)
        alignBuffer(buf) // strings are aligned to 4 bytes
        return String(bytes, Charsets.UTF_8)
    }

    private fun alignBuffer(buf: ByteBuffer) {
        val rem = buf.position() % 4
        if (rem != 0) buf.position(buf.position() + (4 - rem))
    }
}
