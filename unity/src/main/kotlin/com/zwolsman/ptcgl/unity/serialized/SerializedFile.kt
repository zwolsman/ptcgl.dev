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
        val r = EndianBinaryReader(bytes)

        // --- Common header (all versions) ---
        var metadataSize = r.readInt32()
        var fileSize     = r.readInt32().toLong()
        val version      = r.readInt32()
        var dataOffset   = r.readInt32().toLong()

        check(version in 17..22) { "Unsupported SerializedFile version $version (expected 17-22)" }

        // Versions ≥ 9: endianness byte + 3 reserved bytes
        val littleEndian = r.readUByte() == 0
        r.skip(3) // reserved

        // Version 22 (Unity 2020+): large 64-bit fields override the 32-bit ones above
        if (version >= 22) {
            metadataSize = r.readUInt32().toInt()
            fileSize     = r.readInt64()
            dataOffset   = r.readInt64()
            r.readInt64() // unknown / padding
        }

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

    /**
     * Unity's built-in common string table (partial; covers types used in asset manifests).
     * Full table: https://github.com/Unity-Technologies/UnityCsReference/blob/master/Runtime/Export/Serialization/BinaryFormats.cs
     */
    private val kCommonStrings: Map<Int, String> = mapOf(
        0   to "AABB",
        5   to "AnimationClip",
        19  to "AnimationCurve",
        34  to "AnimationState",
        49  to "Array",
        55  to "Base",
        60  to "BitField",
        69  to "bitset",
        76  to "bool",
        81  to "char",
        86  to "ColorRGBA",
        96  to "Component",
        106 to "data",
        111 to "deque",
        117 to "double",
        124 to "dynamic_array",
        138 to "FastPropertyName",
        155 to "first",
        161 to "float",
        167 to "Font",
        172 to "GameObject",
        183 to "Generic Mono",
        196 to "GradientNEW",
        208 to "GUID",
        213 to "GUIStyle",
        222 to "int",
        226 to "list",
        231 to "long long",
        241 to "map",
        245 to "Matrix4x4f",
        256 to "MdFour",
        263 to "MonoBehaviour",
        277 to "MonoManager",
        289 to "MonoObject",
        300 to "MonoProperty",
        313 to "MonoScript",
        324 to "m_ByteSize",
        335 to "m_Curve",
        343 to "m_EditorClassIdentifier",
        368 to "m_EditorHideFlags",
        387 to "m_Enabled",
        397 to "m_ExtensionPtr",
        413 to "m_GameObject",
        426 to "m_Index",
        434 to "m_IsArray",
        444 to "m_IsStatic",
        455 to "m_MetaFlag",
        466 to "m_Name",
        473 to "m_ObjectHideFlags",
        492 to "m_PrefabInternal",
        510 to "m_PrefabParentObject",
        532 to "m_Script",
        541 to "m_StaticEditorFlags",
        562 to "m_Type",
        569 to "m_Version",
        580 to "Object",
        587 to "pair",
        592 to "PPtr<Component>",
        609 to "PPtr<GameObject>",
        627 to "PPtr<Material>",
        643 to "PPtr<MonoBehaviour>",
        664 to "PPtr<MonoManager>",
        683 to "PPtr<Object>",
        697 to "PPtr<Prefab>",
        710 to "PPtr<Sprite>",
        723 to "PPtr<TextAsset>",
        739 to "PPtr<Texture>",
        754 to "PPtr<Texture2D>",
        771 to "PPtr<Transform>",
        788 to "Prefab",
        795 to "Quaternionf",
        807 to "Rectf",
        813 to "RectInt",
        821 to "RectOffset",
        832 to "second",
        839 to "set",
        843 to "short",
        849 to "size",
        854 to "SInt16",
        861 to "SInt32",
        868 to "SInt64",
        875 to "SInt8",
        881 to "staticvector",
        894 to "string",
        901 to "TextAsset",
        911 to "TextMesh",
        920 to "Texture",
        928 to "Texture2D",
        938 to "Transform",
        948 to "TypelessData",
        961 to "UInt16",
        968 to "UInt32",
        975 to "UInt64",
        982 to "UInt8",
        988 to "unsigned int",
        1001 to "unsigned long long",
        1021 to "unsigned short",
        1037 to "vector",
        1044 to "Vector2f",
        1053 to "Vector3f",
        1062 to "Vector4f",
        1071 to "m_ScriptingClassIdentifier",
        1099 to "Gradient",
        1108 to "Type*",
        1114 to "int2_storage",
        1128 to "int3_storage",
        1142 to "BoundsInt",
        1152 to "m_CorrespondingSourceObject",
        1181 to "m_PrefabInstance",
        1199 to "m_PrefabAsset",
        1214 to "FileSize",
    )
}
