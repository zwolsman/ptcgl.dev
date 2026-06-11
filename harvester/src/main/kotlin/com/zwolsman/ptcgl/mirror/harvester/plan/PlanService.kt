package com.zwolsman.ptcgl.mirror.harvester.plan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerEntry
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerRepository
import com.zwolsman.ptcgl.mirror.harvester.db.CardRepository
import com.zwolsman.ptcgl.mirror.harvester.db.ConfigRevisionRepository
import com.zwolsman.ptcgl.mirror.harvester.db.SetRepository
import com.zwolsman.ptcgl.mirror.harvester.domain.SetRecord
import com.zwolsman.ptcgl.mirror.harvester.download.AssetDownloadService
import com.zwolsman.ptcgl.mirror.harvester.normalize.CardDbNormalizer
import com.zwolsman.ptcgl.mirror.harvester.normalize.SetManifestParser
import com.zwolsman.ptcgl.mirror.rainier.cdn.AssetNotFoundException
import com.zwolsman.ptcgl.mirror.rainier.cdn.CdnClient
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDocClient
import com.zwolsman.ptcgl.unity.bundle.UnityBundle
import com.zwolsman.ptcgl.unity.manifest.AssetManifestExtractor
import com.zwolsman.ptcgl.unity.serialized.SerializedFileParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

private val log = LoggerFactory.getLogger(PlanService::class.java)
private val mapper = jacksonObjectMapper()

@Service
class PlanService(
    private val configClient: ConfigDocClient,
    private val cdnClient: CdnClient,
    private val setRepo: SetRepository,
    private val cardRepo: CardRepository,
    private val assetRepo: AssetLedgerRepository,
    private val revisionRepo: ConfigRevisionRepository,
    private val assetDownloadService: AssetDownloadService,
) {

    /**
     * @param setFilter  if non-null, only process this set's card database (e.g. "sv8")
     * @param latestOnly if true, resolve the set with the most recent release date and process only that one
     */
    fun run(locale: String = "en", setFilter: String? = null, latestOnly: Boolean = false) {
        log.info("Phase A starting (locale={}, setFilter={}, latestOnly={})", locale, setFilter, latestOnly)
        assetRepo.reclaimExpiredLeases()

        val setManifestId     = "set-manifest_0.0"
        val bundleManifestId  = "asset-bundle-manifest_0.0"

        val cardDbManifestId  = "card-databases-manifest_0.0"

        // --- 1. Fetch control-plane docs (always fetch to get revisions) ---
        log.info("Fetching config docs: {}, {}, {}", setManifestId, bundleManifestId, cardDbManifestId)
        val (setManifestDoc, bundleManifestDoc, cardDbManifestDoc) =
            configClient.getMultiple(setManifestId, bundleManifestId, cardDbManifestId)

        // --- 2. Sets (skip if unchanged) ---
        val allSets: List<SetRecord>
        if (revisionRepo.isUpToDate(setManifestId, setManifestDoc.revision)) {
            log.info("set-manifest unchanged (rev={}), skipping set upsert", setManifestDoc.revision)
            allSets = SetManifestParser.parse(setManifestDoc)
        } else {
            log.info("Processing set-manifest (rev={})", setManifestDoc.revision)
            allSets = SetManifestParser.parse(setManifestDoc)
            log.info("Found {} sets", allSets.size)
            setRepo.upsertSets(allSets)
            allSets.forEach { s ->
                setRepo.upsertLocalizations(listOf(
                    com.zwolsman.ptcgl.mirror.harvester.domain.SetLocalizationRecord(s.id, locale, s.name)
                ))
            }
            revisionRepo.save(setManifestId, setManifestDoc.revision)
            log.info("Sets upserted")
        }

        // --- 3. Card databases ---
        // Resolve which set codes to process
        val targetCodes: List<String> = when {
            setFilter != null -> {
                require(allSets.any { it.id == setFilter }) {
                    "Unknown set code '$setFilter'. Known: ${allSets.map { it.id }}"
                }
                listOf(setFilter)
            }
            latestOnly -> {
                val latest = allSets.maxByOrNull { it.releaseDate ?: LocalDate.MIN }
                    ?: error("set-manifest is empty")
                log.info("Latest set by release date: {} ({})", latest.id, latest.releaseDate)
                listOf(latest.id)
            }
            else -> allSets.map { it.id }
        }

        // Parse card-databases-manifest: templates look like "sv1_1_{0}", "me4_2_{0}", etc.
        // {0} is replaced with the locale to form the doc ID suffix.
        val cardDbEntry = cardDbManifestDoc["card-databases-manifest"]
            ?: error("card-databases-manifest missing 'card-databases-manifest' key")
        val allTemplates = mapper.readValue(cardDbEntry.payloadBase64, List::class.java)
            .filterIsInstance<String>()
        log.info("Found {} card-DB templates", allTemplates.size)

        log.info("Processing card databases for {} set(s): {}", targetCodes.size, targetCodes)
        for (setCode in targetCodes) {
            // Templates whose prefix matches the set code, e.g. "me4_1_{0}" for setCode "me4"
            val templates = allTemplates.filter { it.startsWith("${setCode}_") }
            if (templates.isEmpty()) {
                log.warn("No card-DB templates found for set={}", setCode)
                continue
            }
            for (template in templates) {
                val docId = "card-database-${template.replace("{0}", locale)}_0.0"
                try {
                    processCardDatabase(docId, setCode, locale)
                } catch (e: Exception) {
                    log.warn("Skipping card-database {}: {}", docId, e.message)
                }
            }
        }

        // --- 4. Derive expected asset names from cards and sets in the DB ---
        val cardKeys = cardRepo.findDistinctCardKeys()    // (set_id, number)
        val setIds   = setRepo.findAllSetIds()
        val expectedAssets = deriveExpectedAssetNames(cardKeys, setIds, locale)
        log.info("Expecting {} asset names from {} card keys and {} sets",
            expectedAssets.size, cardKeys.size, setIds.size)

        // --- 5. Asset-bundle manifest → bucket list (rebuild ledger only when manifest changed) ---
        if (!revisionRepo.isUpToDate(bundleManifestId, bundleManifestDoc.revision)) {
            val manifestEntry = bundleManifestDoc["manifest"]
                ?: error("asset-bundle-manifest missing 'manifest' key")
            @Suppress("UNCHECKED_CAST")
            val buckets = (mapper.readValue(manifestEntry.payloadBase64, Map::class.java)["directories"] as? List<*>)
                ?.filterIsInstance<String>()
                ?: error("asset-bundle-manifest missing 'directories' array")

            log.info("Found {} CDN buckets", buckets.size)

            // --- 6. Download per-bucket manifest bundles → parse → filter → accumulate ledger entries ---
            val desired = mutableListOf<AssetLedgerEntry>()

            for (bucket in buckets) {
                val entries = processBucketManifest(bucket, locale, expectedAssets)
                desired += entries
            }

            log.info("Upserting {} asset_object ledger entries (filtered from full manifest)", desired.size)
            assetRepo.upsertDesiredAssets(desired)

            val activeAssets = desired.map { it.assetName to it.locale }.toSet()
            assetRepo.markStale(activeAssets)

            revisionRepo.save(bundleManifestId, bundleManifestDoc.revision)
            log.info("Phase A ledger complete. {} desired assets in ledger.", desired.size)
        } else {
            log.info("asset-bundle-manifest unchanged (rev={}), skipping ledger rebuild", bundleManifestDoc.revision)
        }

        // --- 6. Download PENDING assets from CDN → S3 (always runs) ---
        log.info("Phase B: downloading assets from CDN to S3…")
        val downloaded = assetDownloadService.downloadAll()
        log.info("Phase B complete. {} assets uploaded to S3.", downloaded)
    }

    private fun processCardDatabase(docId: String, setCode: String, locale: String) {
        val doc = try {
            configClient.getMultiple(docId).first()
        } catch (e: Exception) {
            log.warn("Card DB doc not found for set={}: {}", setCode, e.message)
            return
        }

        if (revisionRepo.isUpToDate(docId, doc.revision)) {
            log.debug("Card DB {} unchanged, skipping", docId)
            return
        }

        log.info("Processing {} (rev={})", docId, doc.revision)
        val entry = doc["card-database"] ?: doc.data.values.firstOrNull()
            ?: run { log.warn("No data entry in {}", docId); return }

        val table = DataTableCodec.decodeFromBase64(entry.payloadBase64)
        val result = CardDbNormalizer.normalize(table, locale)
        cardRepo.upsertCards(result.cards)
        cardRepo.upsertLocalizations(result.localizations)
        revisionRepo.save(docId, doc.revision)
        log.info("Upserted {} cards from {}", result.cards.size, setCode)
    }

    /**
     * Derives the complete set of asset names we care about from the cards and sets already in the DB.
     *
     * Card assets: {set_id}_{locale}_{number}        (HIRES)
     *              {set_id}_{locale}_{number}_t       (THUMB)
     * Set assets:  expansion_{set_id}_{locale}        (set logo/expansion image)
     */
    private fun deriveExpectedAssetNames(
        cardKeys: List<Pair<String, String>>,
        setIds: List<String>,
        locale: String,
    ): Set<String> {
        val names = mutableSetOf<String>()
        for ((setId, number) in cardKeys) {
            names += "${setId}_${locale}_${number}"
            names += "${setId}_${locale}_${number}_t"
        }
        for (setId in setIds) {
            names += "expansion_${setId}_${locale}"
        }
        return names
    }

    private fun processBucketManifest(
        bucket: String,
        locale: String,
        expectedAssets: Set<String>,
    ): List<AssetLedgerEntry> {
        val bundleBytes = try {
            cdnClient.downloadManifestBundle(bucket, locale)
        } catch (e: AssetNotFoundException) {
            log.debug("No manifest bundle for bucket={}, locale={}", bucket, locale)
            return emptyList()
        }

        return try {
            val files = UnityBundle.parse(bundleBytes)
            val sf = files.firstOrNull()?.let { SerializedFileParser.parse(it.data) }
                ?: return emptyList()
            val manifestEntries = AssetManifestExtractor.extract(sf)

            manifestEntries
                .filter { it.assetName in expectedAssets }
                .map { entry ->
                    AssetLedgerEntry(
                        assetName  = entry.assetName,
                        locale     = locale,
                        bucket     = bucket,
                        crc        = entry.crc,
                        sourceHash = entry.hash,
                    )
                }
        } catch (e: Exception) {
            log.warn("Failed to parse manifest bundle for bucket={}: {}", bucket, e.message)
            emptyList()
        }
    }
}
