package com.zwolsman.ptcgl.mirror.harvester.plan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerEntry
import com.zwolsman.ptcgl.mirror.harvester.db.AssetLedgerRepository
import com.zwolsman.ptcgl.mirror.harvester.db.CardRepository
import com.zwolsman.ptcgl.mirror.harvester.db.ConfigRevisionRepository
import com.zwolsman.ptcgl.mirror.harvester.db.RarityRepository
import com.zwolsman.ptcgl.mirror.harvester.db.SetRepository
import com.zwolsman.ptcgl.mirror.harvester.domain.RarityLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.SeriesLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.SetLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.SetRecord
import com.zwolsman.ptcgl.mirror.harvester.normalize.LocalizationBundleDownloader
import com.zwolsman.ptcgl.mirror.harvester.normalize.RarityManifestParser
import com.zwolsman.ptcgl.mirror.harvester.download.AssetDecodeService
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
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.LocalDate

private val log = LoggerFactory.getLogger(PlanService::class.java)
private val mapper = jacksonObjectMapper()

@Service
class PlanService(
    @Lazy private val configClient: ConfigDocClient,
    private val cdnClient: CdnClient,
    private val setRepo: SetRepository,
    private val cardRepo: CardRepository,
    private val assetRepo: AssetLedgerRepository,
    private val revisionRepo: ConfigRevisionRepository,
    private val assetDownloadService: AssetDownloadService,
    private val assetDecodeService: AssetDecodeService,
    private val rarityRepo: RarityRepository,
    private val localizationDownloader: LocalizationBundleDownloader,
) {

    /**
     * @param setFilter  if non-null, only process this set's card database (e.g. "sv8")
     * @param latestOnly if true, resolve the set with the most recent release date and process only that one
     * @param force      if true, reprocess card databases even when the revision is unchanged
     */
    fun run(locale: String = "en", setFilter: String? = null, latestOnly: Boolean = false, force: Boolean = false) {
        log.info("Phase A starting (locale={}, setFilter={}, latestOnly={})", locale, setFilter, latestOnly)
        assetRepo.reclaimExpiredLeases()

        val setManifestId      = "set-manifest_0.0"
        val bundleManifestId   = "asset-bundle-manifest_0.0"
        val cardDbManifestId   = "card-databases-manifest_0.0"
        val rarityManifestId   = "rarity-manifest_0.0"
        val locBundleManifestId = "localization-bundle-manifest_0.0"

        // --- 1. Fetch control-plane docs (always fetch to get revisions) ---
        log.info("Fetching config docs")
        val (setManifestDoc, bundleManifestDoc, cardDbManifestDoc, rarityManifestDoc, locBundleManifestDoc) =
            configClient.getMultiple(setManifestId, bundleManifestId, cardDbManifestId, rarityManifestId, locBundleManifestId)

        // Always parse sets — needed for localization keys and set filter logic below.
        val allSets = SetManifestParser.parse(setManifestDoc)

        // --- 1b. Download localization bundles (skip if unchanged) ---
        val locTables: Map<String, Map<String, String>>
        if (revisionRepo.isUpToDate(locBundleManifestId, locBundleManifestDoc.revision)) {
            log.info("localization-bundle-manifest unchanged (rev={}), skipping download", locBundleManifestDoc.revision)
            locTables = emptyMap()
        } else {
            log.info("Downloading localization bundles (rev={})", locBundleManifestDoc.revision)
            locTables = localizationDownloader.download(locBundleManifestDoc)

            // Upsert set and series localizations for all downloaded locales.
            val allSeriesIds = allSets.mapNotNull { it.series }.distinct()
            for ((loc, table) in locTables) {
                val setRows = allSets.mapNotNull { s ->
                    val name = table["tcg_${s.id}"]?.let(localizationDownloader::stripHtml) ?: return@mapNotNull null
                    SetLocalizationRecord(s.id, loc, name)
                }
                if (setRows.isNotEmpty()) setRepo.upsertLocalizations(setRows)

                val seriesRows = allSeriesIds.mapNotNull { seriesId ->
                    val name = table["tcg_${seriesId.lowercase()}"]?.let(localizationDownloader::stripHtml) ?: return@mapNotNull null
                    SeriesLocalizationRecord(seriesId, loc, name)
                }
                if (seriesRows.isNotEmpty()) setRepo.upsertSeriesLocalizations(seriesRows)
            }

            revisionRepo.save(locBundleManifestId, locBundleManifestDoc.revision)
            log.info("Localizations upserted for {} locales", locTables.size)
        }

        // English table used as the primary localization source for display names.
        val enTable = locTables["en"] ?: emptyMap()

        // --- 2. Sets (skip if unchanged) ---
        if (revisionRepo.isUpToDate(setManifestId, setManifestDoc.revision)) {
            log.info("set-manifest unchanged (rev={}), skipping set upsert", setManifestDoc.revision)
        } else {
            log.info("Processing set-manifest (rev={}), {} sets", setManifestDoc.revision, allSets.size)
            setRepo.upsertSets(allSets)
            revisionRepo.save(setManifestId, setManifestDoc.revision)
            log.info("Sets upserted")
        }

        // --- 2b. Rarities (skip if unchanged) ---
        if (revisionRepo.isUpToDate(rarityManifestId, rarityManifestDoc.revision)) {
            log.info("rarity-manifest unchanged (rev={}), skipping rarity upsert", rarityManifestDoc.revision)
        } else {
            log.info("Processing rarity-manifest (rev={})", rarityManifestDoc.revision)
            val rarities = RarityManifestParser.parse(rarityManifestDoc, enTable)
            log.info("Found {} rarities", rarities.size)
            rarityRepo.upsert(rarities)

            // Upsert locale-specific rarity display names if we have localization data.
            if (locTables.isNotEmpty()) {
                for ((loc, table) in locTables) {
                    val rows = rarities.mapNotNull { r ->
                        val name = table["tcg_rarity_${r.code.lowercase()}"]
                            ?.let(localizationDownloader::extractRarityName)
                            ?: return@mapNotNull null
                        RarityLocalizationRecord(r.code, loc, name)
                    }
                    if (rows.isNotEmpty()) rarityRepo.upsertLocalizations(rows)
                }
            }

            revisionRepo.save(rarityManifestId, rarityManifestDoc.revision)
            log.info("Rarities upserted")
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
                // Primary locale: full upsert (cards + attacks + localizations).
                // All other supported locales: localizations only — card and attack records
                // are locale-invariant and already written by the primary locale pass.
                for (loc in LocalizationBundleDownloader.SUPPORTED_LOCALES) {
                    val docId = "card-database-${template.replace("{0}", loc)}_0.0"
                    val localizationsOnly = loc != locale
                    try {
                        processCardDatabase(docId, setCode, loc, force, localizationsOnly)
                    } catch (e: Exception) {
                        log.warn("Skipping card-database {}: {}", docId, e.message)
                    }
                }
            }
        }

        // --- 4. Derive expected asset names from cards and sets in the DB ---
        // When scoped (--latest / --set), only include the target set's assets so the ledger
        // stays focused and markStale doesn't incorrectly flag other sets' pending assets.
        val scopedSetIds = if (setFilter != null || latestOnly) targetCodes else null
        val cardKeys = cardRepo.findDistinctCardKeys(scopedSetIds)
        val setIds   = scopedSetIds ?: setRepo.findAllSetIds()
        val expectedAssets = deriveExpectedAssetNames(cardKeys, setIds, locale)
        log.info("Expecting {} asset names from {} card keys and {} sets",
            expectedAssets.size, cardKeys.size, setIds.size)

        // --- 5. Asset-bundle manifest → bucket list → rebuild ledger ---
        // Always rebuild: expected assets are derived from the DB (which changes with scope/set filter),
        // so a matching manifest revision is not sufficient to skip this step.
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

        // Only mark stale on a full run — scoped runs must not touch other sets' pending assets.
        if (scopedSetIds == null) {
            val activeAssets = desired.map { it.assetName to it.locale }.toSet()
            assetRepo.markStale(activeAssets)
        }

        revisionRepo.save(bundleManifestId, bundleManifestDoc.revision)
        log.info("Phase A ledger complete. {} desired assets in ledger.", desired.size)

        // --- 6. Download PENDING assets from CDN → S3 (always runs) ---
        val scopeFilter = scopedSetIds
        val pendingCount = assetRepo.countPending(scopeFilter)
        log.info("Phase B: {} assets pending download from CDN to S3…", pendingCount)
        val downloaded = assetDownloadService.downloadAll(setIds = scopeFilter)
        log.info("Phase B complete. {} / {} assets uploaded to S3.", downloaded, pendingCount)

        // --- 7. Decode raw bundles → extract internal files (always runs) ---
        val undecoded = assetRepo.countDoneWithoutDecoded(scopeFilter)
        log.info("Phase C: {} bundles pending unpack from S3…", undecoded)
        val decoded = assetDecodeService.decodeAll(setIds = scopeFilter)
        log.info("Phase C complete. {} / {} bundles unpacked.", decoded, undecoded)
    }

    private fun processCardDatabase(
        docId: String,
        setCode: String,
        locale: String,
        force: Boolean = false,
        localizationsOnly: Boolean = false,
    ) {
        val doc = try {
            configClient.getMultiple(docId).first()
        } catch (e: Exception) {
            log.warn("Card DB doc not found for set={}: {}", setCode, e.message)
            return
        }

        if (!force && revisionRepo.isUpToDate(docId, doc.revision)) {
            log.debug("Card DB {} unchanged, skipping", docId)
            return
        }

        log.info("Processing {} (rev={}, localizationsOnly={})", docId, doc.revision, localizationsOnly)
        val entry = doc["card-database"] ?: doc.data.values.firstOrNull()
            ?: run { log.warn("No data entry in {}", docId); return }

        val table = DataTableCodec.decodeFromBase64(entry.payloadBase64)
        val result = CardDbNormalizer.normalize(table, locale)
        if (!localizationsOnly) {
            cardRepo.upsertCards(result.cards)
            cardRepo.upsertAttacks(result.attacks)
        }
        cardRepo.upsertLocalizations(result.localizations)
        cardRepo.upsertAttackLocalizations(result.attackLocalizations)
        revisionRepo.save(docId, doc.revision)
        log.info("Upserted {} cards ({} attacks) from {} [locale={}]",
            result.cards.size, result.attacks.size, setCode, locale)
    }

    /**
     * Derives the complete set of asset names we care about from the cards and sets already in the DB.
     *
     * Card assets: {set_id}_{locale}_{number}        (HIRES)
     *              {set_code}_{locale}_{padded_number}_t   (THUMB)
     * Set assets:  expansion_{set_id}_{locale}             (set logo/expansion image)
     *
     * Both set_code and padded_number are derived from the card ID, not from the DB set_id/number
     * columns. This correctly handles alt-art sets (e.g. svalt_1 → svalt_en_001) whose DB set_id
     * points to the original set (sv1) but whose CDN bundle name uses the alt set prefix.
     */
    private fun deriveExpectedAssetNames(
        cardIds: List<String>,
        setIds: List<String>,
        locale: String,
    ): Set<String> {
        val names = mutableSetOf<String>()
        for (cardId in cardIds) {
            val (setCode, paddedNumber) = bundleKey(cardId) ?: continue
            names += "${setCode}_${locale}_${paddedNumber}"
            names += "${setCode}_${locale}_${paddedNumber}_t"
        }
        for (setId in setIds) {
            names += "expansion_${setId}_${locale}"
        }
        return names
    }

    /**
     * Derives (set_code, zero-padded-number) from a card ID for CDN bundle name construction.
     *
     * Examples:
     *   svalt_1     → (svalt, 001)
     *   me4_10_ph   → (me4,   010)
     *   sv3-5_133   → (sv3-5, 133)
     */
    private fun bundleKey(cardId: String): Pair<String, String>? {
        val sep = cardId.indexOf('_')
        if (sep < 0) return null
        val setCode = cardId.substring(0, sep)
        val rest    = cardId.substring(sep + 1)
        val number  = rest.substringBefore('_').toIntOrNull() ?: return null
        return setCode to "%03d".format(number)
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
