package com.zwolsman.ptcgl.unity.serialized

/**
 * One node in a Unity type tree.
 *
 * Unity type trees are depth-first pre-order: children of a node immediately follow it
 * with depth = parent.depth + 1.
 *
 * @param typeName  Unity type name (e.g. "string", "vector", "int", "SInt32", "UInt8")
 * @param fieldName Field name within the parent structure
 * @param size      On-disk byte size of the value; -1 for variable-length types
 * @param isArray   True if this node represents an Array (pair: [size int32, data T])
 * @param metaFlags Bit 0x4000 = "align" — align reader to 4 bytes after reading this value
 */
data class TypeTreeNode(
    val version: Int,
    val depth: Int,
    val isArray: Boolean,
    val typeName: String,
    val fieldName: String,
    val size: Int,
    val index: Int,
    val metaFlags: Int,
)

data class SerializedType(
    val classId: Int,
    val isStripped: Boolean,
    val scriptTypeIndex: Short,
    val scriptId: ByteArray?,      // 16 bytes; present only for MonoBehaviour (classId 114)
    val oldTypeHash: ByteArray,    // 16 bytes
    val nodes: List<TypeTreeNode>,
)

data class ObjectInfo(
    val pathId: Long,
    val byteStart: Long,
    val byteSize: Int,
    val typeIndex: Int,
)
