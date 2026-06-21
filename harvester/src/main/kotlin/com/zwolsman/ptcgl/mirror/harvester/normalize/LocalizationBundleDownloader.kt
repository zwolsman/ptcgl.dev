package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zwolsman.ptcgl.mirror.rainier.cdn.AssetNotFoundException
import com.zwolsman.ptcgl.mirror.rainier.cdn.CdnClient
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDoc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private val log = LoggerFactory.getLogger(LocalizationBundleDownloader::class.java)
private val mapper = jacksonObjectMapper()
private val HTML_TAG_REGEX = Regex("<[^>]+>")

@Component
class LocalizationBundleDownloader(private val cdnClient: CdnClient) {

    companion object {
        val SUPPORTED_LOCALES = listOf("en", "fr", "de", "es", "it", "pt")
    }

    /**
     * Downloads all localization gzip files referenced by the manifest and merges them into
     * a per-locale flat map. Later buckets override earlier ones for duplicate keys.
     *
     * @return Map of locale → (key → cleaned value), e.g. {"en" → {"tcg_sv1" → "Scarlet & Violet"}}
     */
    fun download(locBundleManifestDoc: ConfigDoc): Map<String, Map<String, String>> {
        val entry = locBundleManifestDoc["manifest"]
            ?: error("localization-bundle-manifest missing 'manifest' key")
        @Suppress("UNCHECKED_CAST")
        val directories = (mapper.readValue(entry.payloadBase64, Map::class.java)["directories"] as? List<*>)
            ?.filterIsInstance<String>()
            ?: error("localization-bundle-manifest missing 'directories' array")

        log.info("Found {} localization directories", directories.size)

        return SUPPORTED_LOCALES.associateWith { locale ->
            val merged = mutableMapOf<String, String>()
            for (dir in directories) {
                try {
                    val bytes = cdnClient.downloadRaw("$dir/$locale.gzip")
                    val json = GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readText()
                    val dict = mapper.readValue<Map<String, String>>(json)
                    merged.putAll(dict)
                } catch (e: AssetNotFoundException) {
                    log.debug("No localization file: dir={}, locale={}", dir, locale)
                } catch (e: Exception) {
                    log.warn("Failed to read localization dir={}, locale={}: {}", dir, locale, e.message)
                }
            }
            log.info("Locale {} merged {} localization keys from {} directories", locale, merged.size, directories.size)
            merged
        }
    }

    /**
     * Strips HTML italic tags and replaces non-breaking spaces.
     * e.g. "<i>Scarlet & Violet</i>" → "Scarlet & Violet"
     */
    fun stripHtml(value: String): String =
        HTML_TAG_REGEX.replace(value, "").replace(' ', ' ').trim()

    /**
     * Extracts the rarity display name from a localization value that uses a {0} card-name placeholder.
     * e.g. "{0} common" → "Common", "{0} double rare" → "Double Rare"
     */
    fun extractRarityName(value: String): String {
        val stripped = value.replace(Regex("\\{0}[\\s ]*"), "").trim()
        return stripped.replaceFirstChar { it.uppercase() }
    }
}
