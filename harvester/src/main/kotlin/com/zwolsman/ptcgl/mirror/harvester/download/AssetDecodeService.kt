package com.zwolsman.ptcgl.mirror.harvester.download

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerRepository
import com.zwolsman.ptcgl.mirror.harvester.db.MaterialManifestEntry
import com.zwolsman.ptcgl.unity.bundle.BundleFile
import com.zwolsman.ptcgl.unity.bundle.UnityBundle
import com.zwolsman.ptcgl.unity.serialized.SerializedFileParser
import com.zwolsman.ptcgl.unity.serialized.TypeTreeReader
import com.zwolsman.ptcgl.unity.texture.TextureDecoder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

private val log = LoggerFactory.getLogger(AssetDecodeService::class.java)
private val mapper = jacksonObjectMapper()

private const val CLASS_TEXTURE2D    = 28
private const val CLASS_MONO_BEHAVIOUR = 114

@Service
class AssetDecodeService(
    private val assetRepo: AssetLedgerRepository,
    private val s3: S3Client,
    @Value("\${mirror.s3.bucket}") private val bucket: String,
) {

    /**
     * For every DONE raw bundle:
     *   1. Fetches it from S3
     *   2. Parses the UnityFS container → List<BundleFile> (CAB + optional .resS)
     *   3. Parses each CAB as a SerializedFile
     *   4. Extracts Texture2D image data (from embedded bytes or the paired .resS)
     *   5. Stores only the texture bytes; CAB/.resS are discarded
     *
     * S3 key for extracted textures: decoded/{s3_key_raw}/{m_Name}
     * s3_key_decoded is set to the decoded/ prefix.
     */
    fun decodeAll(batchSize: Int = 50, setIds: List<String>? = null): Int {
        var total = 0
        var failed = 0

        while (true) {
            val batch = assetRepo.findDoneWithoutDecoded(batchSize, setIds)
            if (batch.isEmpty()) break

            log.info("Decode batch: {} bundles (decoded={}, failed={})", batch.size, total, failed)

            for (asset in batch) {
                try {
                    val rawBytes = s3.getObjectAsBytes { it.bucket(bucket).key(asset.s3KeyRaw) }.asByteArray()
                    val bundleFiles = UnityBundle.parse(rawBytes)

                    val decodedPrefix = "decoded/${asset.s3KeyRaw}"
                    val result = extractAndUpload(bundleFiles, decodedPrefix)

                    if (result.extractedCount > 0) {
                        assetRepo.markDecoded(
                            assetName    = asset.assetName,
                            locale       = asset.locale,
                            s3KeyDecoded = decodedPrefix,
                            textureName  = result.primaryTextureName,
                        )
                        if (result.manifests.isNotEmpty()) {
                            assetRepo.upsertManifests(asset.assetName, result.manifests.map { m ->
                                MaterialManifestEntry(
                                    variantSuffix  = m.variantSuffix,
                                    whiteplateName = m.whiteplateName,
                                    etchName       = m.etchName,
                                    foilType       = m.foilType,
                                    shaderPath     = m.shaderPath,
                                )
                            })
                        }
                        total++
                    } else {
                        log.warn("No assets extracted from {}", asset.s3KeyRaw)

                        failed++
                    }
                } catch (e: Exception) {
                    log.warn("Failed to decode {}: {}", asset.s3KeyRaw, e.message)
                    failed++
                }
            }
        }

        log.info("Decode complete: {} bundles unpacked, {} failed", total, failed)
        return total
    }

    private data class ManifestData(
        val variantSuffix: String,
        val whiteplateName: String?,
        val etchName: String?,
        val foilType: String?,
        val shaderPath: String?,
    )

    private data class DecodeResult(
        val extractedCount: Int,
        val primaryTextureName: String?,
        val manifests: List<ManifestData>,
    )

    /**
     * Splits bundle files into .resS (external texture data) and CAB (SerializedFiles),
     * then extracts all Texture2D objects and MonoBehaviours (e.g. MaterialManifest) and uploads them.
     */
    private fun extractAndUpload(bundleFiles: List<BundleFile>, decodedPrefix: String): DecodeResult {
        // .resS files hold external texture data referenced by offset+size from a CAB
        val resSByName: Map<String, ByteArray> = bundleFiles
            .filter { it.path.endsWith(".resS", ignoreCase = true) }
            .associateBy { it.path.substringAfterLast("/") }
            .mapValues { (_, f) -> f.data }

        val cabFiles = bundleFiles.filter { !it.path.endsWith(".resS", ignoreCase = true) }

        var count = 0
        var firstTextureName: String? = null   // fallback: first Texture2D m_Name encountered
        var hiresFromManifest: String? = null  // _c from base MaterialManifest — explicit hires ref
        val manifests = mutableListOf<ManifestData>()

        for (cab in cabFiles) {
            val sf = try {
                SerializedFileParser.parse(cab.data)
            } catch (e: Exception) {
                log.debug("Failed to parse CAB {}: {}", cab.path, e.message)
                continue
            }

            for (obj in sf.objects) {
                val type = sf.types.getOrNull(obj.typeIndex) ?: continue
                if (type.nodes.isEmpty()) continue

                try {
                    when (type.classId) {
                        CLASS_TEXTURE2D -> {
                            val objData = TypeTreeReader.read(sf, obj)
                            val name   = objData["m_Name"] as? String ?: continue
                            val width  = objData["m_Width"].asInt()  ?: continue
                            val height = objData["m_Height"].asInt() ?: continue
                            val format = objData["m_TextureFormat"].asInt() ?: continue

                            val rawBytes = readTextureData(objData, resSByName) ?: run {
                                log.debug("No image data for texture '{}' in {}", name, cab.path)
                                continue
                            }

                            val pngBytes = TextureDecoder.toPng(rawBytes, width, height, format) ?: run {
                                log.warn("Unsupported texture format {} for '{}' ({}x{}), skipping", format, name, width, height)
                                continue
                            }

                            val key = "$decodedPrefix/$name.png"
                            s3.putObject(
                                PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .contentLength(pngBytes.size.toLong())
                                    .build(),
                                RequestBody.fromBytes(pngBytes),
                            )
                            if (firstTextureName == null) firstTextureName = name
                            count++
                        }

                        CLASS_MONO_BEHAVIOUR -> {
                            val objData = TypeTreeReader.read(sf, obj)
                            val name = (objData["m_Name"] as? String)?.takeIf { it.isNotBlank() } ?: continue

                            if (name.startsWith("MaterialManifest")) {
                                // suffix: "" for standard, "ph" for MaterialManifest_ph, etc.
                                val suffix = name.removePrefix("MaterialManifest").removePrefix("_")
                                // _c names the card's hires texture; same value across all variant manifests
                                if (hiresFromManifest == null) {
                                    hiresFromManifest = (objData["_c"] as? String)?.takeIf { it.isNotBlank() }
                                }
                                manifests += ManifestData(
                                    variantSuffix  = suffix,
                                    whiteplateName = (objData["_w"] as? String)?.takeIf { it.isNotBlank() },
                                    etchName       = (objData["_e"] as? String)?.takeIf { it.isNotBlank() },
                                    foilType       = (objData["_f"] as? String)?.takeIf { it.isNotBlank() },
                                    shaderPath     = (objData["_s"] as? String)?.takeIf { it.isNotBlank() },
                                )
                            }

                            val json = mapper.writeValueAsBytes(objData)
                            val key = "$decodedPrefix/$name.json"
                            s3.putObject(
                                PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .contentLength(json.size.toLong())
                                    .build(),
                                RequestBody.fromBytes(json),
                            )
                            count++
                        }
                    }
                } catch (e: Exception) {
                    log.debug("Failed to extract object (classId={}) from {}: {}", type.classId, cab.path, e.message)
                }
            }
        }
        return DecodeResult(extractedCount = count, primaryTextureName = hiresFromManifest ?: firstTextureName, manifests = manifests)
    }

    /** Coerces Int or Long TypeTree values to Int. */
    private fun Any?.asInt(): Int? = when (this) {
        is Int  -> this
        is Long -> toInt()
        else    -> null
    }

    /**
     * Reads raw texture bytes from a Texture2D TypeTree map.
     * Tries the embedded `image data` field first; falls back to the paired .resS file
     * using the offset and size from `m_StreamData`.
     */
    private fun readTextureData(objData: Map<String, Any?>, resSByName: Map<String, ByteArray>): ByteArray? {
        val embedded = objData["image data"] as? ByteArray
        if (embedded != null && embedded.isNotEmpty()) return embedded

        @Suppress("UNCHECKED_CAST")
        val streamData = objData["m_StreamData"] as? Map<String, Any?> ?: return null

        val offset = when (val o = streamData["offset"]) {
            is Int  -> o.toLong()
            is Long -> o
            else    -> return null
        }
        val size = when (val sz = streamData["size"]) {
            is Int  -> sz.toLong()
            is Long -> sz
            else    -> return null
        }
        if (size == 0L) return null

        val path = streamData["path"] as? String ?: return null
        // path is often "archive:/CAB-xxx/CAB-xxx.resS" — we only need the filename
        val resSName = path.substringAfterLast("/")
        val resSData = resSByName[resSName]
            ?: resSByName.values.firstOrNull()   // single-.resS bundle: match by position
            ?: return null

        if (offset + size > resSData.size) {
            log.debug("resS range [{}, {}) out of bounds (file size {})", offset, offset + size, resSData.size)
            return null
        }
        return resSData.copyOfRange(offset.toInt(), (offset + size).toInt())
    }
}
