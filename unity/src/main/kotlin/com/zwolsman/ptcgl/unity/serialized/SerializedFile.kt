package com.zwolsman.ptcgl.unity.serialized

import com.zwolsman.ptcgl.unity.io.EndianBinaryReader

/**
 * A parsed Unity SerializedFile.
 *
 * @param version       Serialized file format version (22 for Unity 6 / 2020+)
 * @param types         Type definitions with embedded TypeTree nodes
 * @param objects       Object table — each entry locates raw bytes within [data]
 * @param data          Raw concatenated object data (not yet parsed by type)
 */
data class SerializedFile(
    val version: Int,
    val types: List<SerializedType>,
    val objects: List<ObjectInfo>,
    val data: ByteArray,
)

object SerializedFileParser {

    // MonoBehaviour classId — carries a script GUID
    private const val CLASS_MONO_BEHAVIOUR = 114

    // String offsets ≥ this index into Unity's built-in common strings table
    private const val COMMON_STRING_OFFSET = 0x80000000.toInt()

    /**
     * Parse a Unity SerializedFile from raw bytes.
     * Supports format versions 17–22 (Unity 5.5 through Unity 6).
     */
    fun parse(bytes: ByteArray): SerializedFile {
        // UnityFS header fields are big-endian
        var r = EndianBinaryReader(bytes, bigEndian = true)

        // --- Common header (all versions, big-endian) ---
        var metadataSize = r.readInt32()
        var fileSize     = r.readInt32().toLong()
        val version      = r.readInt32()
        var dataOffset   = r.readInt32().toLong()

        check(version in 17..22) { "Unsupported SerializedFile version $version (expected 17-22)" }

        // Endianness byte: 0 = little-endian (almost all Unity files), 1 = big-endian
        val littleEndian = r.readUByte() == 0
        r.skip(3) // reserved

        // Version 22 (Unity 2020+): large 64-bit fields override the 32-bit ones above
        if (version >= 22) {
            metadataSize = r.readInt32()
            fileSize     = r.readInt64()
            dataOffset   = r.readInt64()
            r.readInt64() // unknown / padding
        }

        // Switch to the file's native endianness for the metadata section
        r = r.withBigEndian(!littleEndian)

        // --- Metadata section ---
        r.readCString() // unity version string (e.g. "6000.3.5f2")
        r.readInt32()   // build target (platform enum)
        val enableTypeTree = r.readBoolean()

        // --- Types ---
        val typeCount = r.readInt32()
        val stringBufStart: Int
        val types = buildList(typeCount) {
            repeat(typeCount) {
                val classId         = r.readInt32()
                val isStripped      = r.readBoolean()
                val scriptTypeIndex = r.readInt16()

                val scriptId = if (classId == CLASS_MONO_BEHAVIOUR) r.readBytes(16) else null
                val oldTypeHash = r.readBytes(16)

                val nodes = if (enableTypeTree) readTypeTreeNodes(r, version) else emptyList()

                // v21+ type-dependency list (skip — not needed for manifest parsing)
                val depCount = r.readInt32()
                r.skip(depCount * 4)

                add(SerializedType(classId, isStripped, scriptTypeIndex, scriptId, oldTypeHash, nodes))
            }
            stringBufStart = r.pos // capture after last type's string buffer is consumed
        }

        // --- Objects ---
        val objectCount = r.readInt32()
        val objects = buildList(objectCount) {
            repeat(objectCount) {
                r.align(4)
                val pathId    = r.readInt64()
                val byteStart = if (version >= 22) r.readInt64() else r.readUInt32().toLong()
                val byteSize  = r.readUInt32().toInt()
                val typeIndex = r.readInt32()
                add(ObjectInfo(pathId, byteStart, byteSize, typeIndex))
            }
        }

        // Skip remaining metadata (script types, externals, ref types, user info)
        // We don't need them for manifest parsing.

        val data = bytes.copyOfRange(dataOffset.toInt(), bytes.size)
        return SerializedFile(version, types, objects, data)
    }

    private fun readTypeTreeNodes(r: EndianBinaryReader, version: Int): List<TypeTreeNode> {
        val nodeCount       = r.readInt32()
        val stringBufSize   = r.readInt32()

        val rawNodes = Array(nodeCount) {
            val nodeVersion = r.readUInt16()
            val depth       = r.readUByte()
            val isArray     = r.readBoolean()
            val typeOffset  = r.readUInt32().toInt()
            val nameOffset  = r.readUInt32().toInt()
            val size        = r.readInt32()
            val index       = r.readUInt32().toInt()
            val metaFlags   = r.readUInt32().toInt()
            if (version >= 19) r.readInt64() // refTypeHash
            RawNode(nodeVersion, depth, isArray, typeOffset, nameOffset, size, index, metaFlags)
        }

        val stringBuf = r.readBytes(stringBufSize)

        fun resolveString(offset: Int): String =
            if (offset and COMMON_STRING_OFFSET != 0) {
                kCommonStrings[offset and 0x7FFFFFFF] ?: "unknown"
            } else {
                val sb = EndianBinaryReader(stringBuf, offset)
                sb.readCString()
            }

        return rawNodes.map { n ->
            TypeTreeNode(
                version   = n.version,
                depth     = n.depth,
                isArray   = n.isArray,
                typeName  = resolveString(n.typeOffset),
                fieldName = resolveString(n.nameOffset),
                size      = n.size,
                index     = n.index,
                metaFlags = n.metaFlags,
            )
        }
    }

    private data class RawNode(
        val version: Int, val depth: Int, val isArray: Boolean,
        val typeOffset: Int, val nameOffset: Int,
        val size: Int, val index: Int, val metaFlags: Int,
    )

    private val kCommonStrings: Map<Int, String> = mapOf(
        0    to "AABB",
        5    to "AnimationClip",
        19   to "AnimationCurve",
        34   to "AnimationState",
        49   to "Array",
        55   to "Base",
        60   to "BitField",
        69   to "bitset",
        76   to "bool",
        81   to "char",
        86   to "ColorRGBA",
        96   to "Component",
        106  to "data",
        111  to "deque",
        117  to "double",
        124  to "dynamic_array",
        138  to "FastPropertyName",
        155  to "first",
        161  to "float",
        167  to "Font",
        172  to "GameObject",
        183  to "Generic Mono",
        196  to "GradientNEW",
        208  to "GUID",
        213  to "GUIStyle",
        222  to "int",
        226  to "list",
        231  to "long long",
        241  to "map",
        245  to "Matrix4x4f",
        256  to "MdFour",
        263  to "MonoBehaviour",
        277  to "MonoScript",
        288  to "m_ByteSize",
        299  to "m_Curve",
        307  to "m_EditorClassIdentifier",
        331  to "m_EditorHideFlags",
        349  to "m_Enabled",
        359  to "m_ExtensionPtr",
        374  to "m_GameObject",
        387  to "m_Index",
        395  to "m_IsArray",
        405  to "m_IsStatic",
        416  to "m_MetaFlag",
        427  to "m_Name",
        434  to "m_ObjectHideFlags",
        452  to "m_PrefabInternal",
        469  to "m_PrefabParentObject",
        490  to "m_Script",
        499  to "m_StaticEditorFlags",
        519  to "m_Type",
        526  to "m_Version",
        536  to "Object",
        543  to "pair",
        548  to "PPtr<Component>",
        564  to "PPtr<GameObject>",
        581  to "PPtr<Material>",
        596  to "PPtr<MonoBehaviour>",
        616  to "PPtr<MonoScript>",
        633  to "PPtr<Object>",
        646  to "PPtr<Prefab>",
        659  to "PPtr<Sprite>",
        672  to "PPtr<TextAsset>",
        688  to "PPtr<Texture>",
        702  to "PPtr<Texture2D>",
        718  to "PPtr<Transform>",
        734  to "Prefab",
        741  to "Quaternionf",
        753  to "Rectf",
        759  to "RectInt",
        767  to "RectOffset",
        778  to "second",
        785  to "set",
        789  to "short",
        795  to "size",
        800  to "SInt16",
        807  to "SInt32",
        814  to "SInt64",
        821  to "SInt8",
        827  to "staticvector",
        840  to "string",
        847  to "TextAsset",
        857  to "TextMesh",
        866  to "Texture",
        874  to "Texture2D",
        884  to "Transform",
        894  to "TypelessData",
        907  to "UInt16",
        914  to "UInt32",
        921  to "UInt64",
        928  to "UInt8",
        934  to "unsigned int",
        947  to "unsigned long long",
        966  to "unsigned short",
        981  to "vector",
        988  to "Vector2f",
        997  to "Vector3f",
        1006 to "Vector4f",
        1015 to "m_ScriptingClassIdentifier",
        1042 to "Gradient",
        1051 to "Type*",
        1057 to "int2_storage",
        1070 to "int3_storage",
        1083 to "BoundsInt",
        1093 to "m_CorrespondingSourceObject",
        1121 to "m_PrefabInstance",
        1138 to "m_PrefabAsset",
        1152 to "FileSize",
        1161 to "Hash128",
        1169 to "RenderingLayerMask",
        1188 to "fixed_array",
        1200 to "EntityId",
        1209 to "LoadableReference",
    )
}
