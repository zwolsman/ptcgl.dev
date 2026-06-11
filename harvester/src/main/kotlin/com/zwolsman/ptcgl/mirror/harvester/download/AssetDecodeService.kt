package com.zwolsman.ptcgl.mirror.harvester.download

import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerRepository
import com.zwolsman.ptcgl.unity.bundle.UnityBundle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

private val log = LoggerFactory.getLogger(AssetDecodeService::class.java)

@Service
class AssetDecodeService(
    private val assetRepo: AssetLedgerRepository,
    private val s3: S3Client,
    @Value("\${mirror.s3.bucket}") private val bucket: String,
) {

    /**
     * Reads every DONE raw bundle from S3, unpacks it with UnityBundle, and re-uploads each
     * extracted file under a "decoded/" prefix that mirrors the original CDN path structure.
     *
     * S3 layout:
     *   raw:     {bucket}/{assetName}                         (e.g. 10101_0000/me4_en_001)
     *   decoded: decoded/{bucket}/{assetName}/{internal_path} (e.g. decoded/10101_0000/me4_en_001/CAB-abc)
     *
     * s3_key_decoded is set to the decoded directory prefix so callers can list all
     * files extracted from the bundle.
     *
     * @return total number of bundles decoded
     */
    fun decodeAll(batchSize: Int = 50): Int {
        var total = 0
        var failed = 0

        while (true) {
            val batch = assetRepo.findDoneWithoutDecoded(batchSize)
            if (batch.isEmpty()) break

            log.info("Decode batch: {} bundles (decoded={}, failed={})", batch.size, total, failed)

            for (asset in batch) {
                try {
                    val rawBytes = s3.getObjectAsBytes { it.bucket(bucket).key(asset.s3KeyRaw) }.asByteArray()
                    val bundleFiles = UnityBundle.parse(rawBytes)

                    val decodedPrefix = "decoded/${asset.s3KeyRaw}"

                    for (file in bundleFiles) {
                        val decodedKey = "$decodedPrefix/${file.path}"
                        s3.putObject(
                            PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(decodedKey)
                                .contentLength(file.data.size.toLong())
                                .build(),
                            RequestBody.fromBytes(file.data),
                        )
                    }

                    assetRepo.markDecoded(asset.assetName, asset.locale, decodedPrefix)
                    total++
                } catch (e: Exception) {
                    log.warn("Failed to decode {}/{}: {}", asset.bucket, asset.assetName, e.message)
                    failed++
                }
            }
        }

        log.info("Decode complete: {} bundles unpacked, {} failed", total, failed)
        return total
    }
}
