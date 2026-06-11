package com.zwolsman.ptcgl.mirror.harvester.plan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerEntry
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerRepository
import com.zwolsman.ptcgl.mirror.harvester.db.CardRepository
import com.zwolsman.ptcgl.mirror.harvester.db.ConfigRevisionRepository
import com.zwolsman.ptcgl.mirror.harvester.db.SetRepository
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
) {

    fun run(locale: String = "en") {
        log.info("Phase A starting (locale={})", locale)
        assetRepo.reclaimExpiredLeases()

        val setManifestId     = "set-manifest_0.0"
        val bundleManifestId  = "asset-bundle-manifest_0.0"

        // --- 1. Fetch control-plane docs (always fetch to get revisions) ---
        log.info("Fetching config docs: {}, {}", setManifestId, bundleManifestId)
        val (setManifestDoc, bundleManifestDoc) = configClient.getMultiple(setManifestId, bundleManifestId)

        // --- 2. Sets (skip if unchanged) ---
        if (revisionRepo.isUpToDate(setManifestId, setManifestDoc.revision)) {
            log.info("set-manifest unchanged (rev={}), skipping sets", setManifestDoc.revision)
        } else {
            log.info("Processing set-manifest (rev={})", setManifestDoc.revision)
            val sets = SetManifestParser.parse(setManifestDoc)
            log.info("Found {} sets", sets.size)
            setRepo.upsertSets(sets)
            sets.forEach { s ->
                setRepo.upsertLocalizations(listOf(
                    com.zwolsman.ptcgl.mirror.harvester.domain.SetLocalizationRecord(s.id, locale, s.name)
                ))
            }
            revisionRepo.save(setManifestId, setManifestDoc.revision)
            log.info("Sets upserted")
        }

        // --- 3. Card databases per set ---
        val setManifestEntry = setManifestDoc["manifest"]
            ?: error("set-manifest missing 'manifest' key")
        val setCodes = mapper.readValue(setManifestEntry.payloadBase64, List::class.java)
            .filterIsInstance<String>()

        for (setCode in setCodes) {
            val docId = "card-database-${setCode}_${locale}_0.0"
            try {
                processCardDatabase(docId, setCode, locale)
            } catch (e: Exception) {
                log.warn("Skipping card-database for set={}: {}", setCode, e.message)
            }
        }

        // --- 4. Asset-bundle manifest → bucket list ---
        if (revisionRepo.isUpToDate(bundleManifestId, bundleManifestDoc.revision)) {
            log.info("asset-bundle-manifest unchanged (rev={}), skipping asset ledger", bundleManifestDoc.revision)
            return
        }

        val manifestEntry = bundleManifestDoc["manifest"]
            ?: error("asset-bundle-manifest missing 'manifest' key")
        @Suppress("UNCHECKED_CAST")
        val buckets = (mapper.readValue(manifestEntry.payloadBase64, Map::class.java)["directories"] as? List<*>)
            ?.filterIsInstance<String>()
            ?: error("asset-bundle-manifest missing 'directories' array")

        log.info("Found {} CDN buckets", buckets.size)

        // --- 5. Download per-bucket manifest bundles → parse → accumulate ledger entries ---
        val desired = mutableListOf<AssetLedgerEntry>()

        for (bucket in buckets) {
            val entries = processBucketManifest(bucket, locale)
            desired += entries
        }

        log.info("Upserting {} asset_object ledger entries", desired.size)
        assetRepo.upsertDesiredAssets(desired)

        val activeAssets = desired.map { it.assetName to it.locale }.toSet()
        assetRepo.markStale(activeAssets)

        revisionRepo.save(bundleManifestId, bundleManifestDoc.revision)
        log.info("Phase A complete. Ledger has {} desired assets.", desired.size)
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

    private fun processBucketManifest(bucket: String, locale: String): List<AssetLedgerEntry> {
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

            manifestEntries.map { entry ->
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
