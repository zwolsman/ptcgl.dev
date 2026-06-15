package com.zwolsman.ptcgl.mirror.harvester.download

import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerRepository
import com.zwolsman.ptcgl.mirror.rainier.cdn.AssetNotFoundException
import com.zwolsman.ptcgl.mirror.rainier.cdn.CdnClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

private val log = LoggerFactory.getLogger(AssetDownloadService::class.java)

@Service
class AssetDownloadService(
    private val cdnClient: CdnClient,
    private val assetRepo: AssetLedgerRepository,
    private val s3: S3Client,
    @Value("\${mirror.s3.bucket}") private val bucket: String,
) {

    /**
     * Claims PENDING assets in batches, downloads each from the CDN, and uploads to S3.
     * Continues until no PENDING rows remain or the process is interrupted.
     *
     * @param batchSize number of assets to claim per iteration
     * @return total number of assets successfully uploaded
     */
    fun downloadAll(batchSize: Int = 50, setIds: List<String>? = null): Int {
        var total = 0
        var skipped = 0
        var failed = 0
        var batch = 0

        while (true) {
            val claimed = assetRepo.claimPending(batchSize, setIds = setIds)
            if (claimed.isEmpty()) break

            batch++
            log.info("Batch {}: claimed {} assets (done={}, skipped={}, failed={})", batch, claimed.size, total, skipped, failed)

            for (asset in claimed) {
                val cdnPath = "${asset.bucket}/${asset.assetName}"
                val s3Key   = cdnPath
                try {
                    val bytes = cdnClient.downloadRaw(cdnPath)
                    s3.putObject(
                        PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(s3Key)
                            .contentLength(bytes.size.toLong())
                            .build(),
                        RequestBody.fromBytes(bytes),
                    )
                    assetRepo.markDone(asset.assetName, asset.locale, s3Key)
                    total++
                } catch (e: AssetNotFoundException) {
                    assetRepo.markFailed(asset.assetName, asset.locale, "Not found on CDN: $cdnPath")
                    skipped++
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    log.warn("Failed to download {}: {}", cdnPath, msg)
                    assetRepo.markFailed(asset.assetName, asset.locale, msg)
                    failed++
                }
            }
        }

        log.info("Asset download complete: {} done, {} skipped (not on CDN), {} failed", total, skipped, failed)
        return total
    }
}
