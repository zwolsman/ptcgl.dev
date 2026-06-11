package com.zwolsman.ptcgl.unity.manifest

import com.zwolsman.ptcgl.unity.serialized.SerializedFile
import com.zwolsman.ptcgl.unity.serialized.TypeTreeReader

data class ManifestEntry(
    val assetName: String,
    val crc: Long,
    val hash: String,
    val s3Folder: String,
    val dependencies: List<String>,
)

private const val CLASS_MONO_BEHAVIOUR = 114

object AssetManifestExtractor {

    /**
     * Extract all [ManifestEntry] records from a Unity AssetManifest MonoBehaviour.
     *
     * The manifest SerializedFile typically contains one MonoBehaviour (classId 114)
     * whose root map has an "assetList" field carrying the array of entries.
     */
    fun extract(file: SerializedFile): List<ManifestEntry> {
        val mono = file.objects.firstOrNull { file.types[it.typeIndex].classId == CLASS_MONO_BEHAVIOUR }
            ?: error("No MonoBehaviour (classId 114) found in SerializedFile")

        @Suppress("UNCHECKED_CAST")
        val root = TypeTreeReader.read(file, mono)

        @Suppress("UNCHECKED_CAST")
        val assetList = root["assetList"] as? List<*>
            ?: error("AssetManifest missing 'assetList' field. Available keys: ${root.keys}")

        return assetList.mapIndexed { idx, item ->
            @Suppress("UNCHECKED_CAST")
            val entry = item as? Map<String, Any?>
                ?: error("assetList[$idx] is not a map: $item")
            ManifestEntry(
                assetName    = entry["assetName"] as? String   ?: "",
                crc          = (entry["crc"] as? Long) ?: ((entry["crc"] as? Int)?.toLong() ?: 0L),
                hash         = entry["hash"] as? String         ?: "",
                s3Folder     = entry["s3Folder"] as? String     ?: "",
                dependencies = (entry["dependencies"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            )
        }
    }
}
