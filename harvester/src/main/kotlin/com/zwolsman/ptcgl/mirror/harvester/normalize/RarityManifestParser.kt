package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDoc
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(RarityManifestParser::class.java)
private val mapper = jacksonObjectMapper()

data class RarityDefinition(
    val code: String,
    val displayName: String,
    val sortValue: Int,
)

object RarityManifestParser {

    /**
     * Parses rarity definitions from the rarity manifest.
     *
     * Display names come from the [localizationTable] under the key "tcg_rarity_{code.lowercase()}".
     * The localization value may contain a "{0}" card-name placeholder which is stripped.
     * Falls back to splitting the InternalRarityName on camelCase word boundaries.
     */
    fun parse(doc: ConfigDoc, localizationTable: Map<String, String> = emptyMap()): List<RarityDefinition> {
        val entry = doc["rarities"] ?: error("rarity-manifest missing 'rarities' key")
        val root  = mapper.readTree(entry.payloadBase64)
        return root.get("rarity-definitions").map { node ->
            val code         = node.get("RarityCode").asText()
            val internalName = node.get("InternalRarityName").asText()
            val locKey       = "tcg_rarity_${code.lowercase()}"
            val displayName  = localizationTable[locKey]
                ?.let { extractRarityName(it) }
                ?: run {
                    log.debug("No localization for rarity code={}, falling back to camelCase split", code)
                    camelToSpaced(internalName)
                }
            RarityDefinition(
                code        = code,
                displayName = displayName,
                sortValue   = node.get("RaritySortValue").asInt(),
            )
        }
    }

    private val CAMEL_SPLIT = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")

    private fun camelToSpaced(camel: String): String =
        CAMEL_SPLIT.findAll(camel).joinToString(" ") { it.value }

    private fun extractRarityName(value: String): String {
        val stripped = value.replace(Regex("\\{0}[\\s ]*"), "").trim()
        return stripped.replaceFirstChar { it.uppercase() }
    }
}
