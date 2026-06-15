package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDoc

private val mapper = jacksonObjectMapper()

data class RarityDefinition(
    val code: String,
    val displayName: String,
    val sortValue: Int,
)

object RarityManifestParser {

    fun parse(doc: ConfigDoc): List<RarityDefinition> {
        val entry = doc["rarities"] ?: error("rarity-manifest missing 'rarities' key")
        val root  = mapper.readTree(entry.payloadBase64)
        return root.get("rarity-definitions").map { node ->
            RarityDefinition(
                code        = node.get("RarityCode").asText(),
                displayName = camelToSpaced(node.get("InternalRarityName").asText()),
                sortValue   = node.get("RaritySortValue").asInt(),
            )
        }
    }

    // Splits camelCase/PascalCase while preserving acronyms:
    //   RareHoloVMAX -> Rare Holo VMAX
    //   MegaHyperRare -> Mega Hyper Rare
    //   GalarianGalleryHoloRare -> Galarian Gallery Holo Rare
    private val CAMEL_SPLIT = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")

    fun camelToSpaced(camel: String): String =
        CAMEL_SPLIT.findAll(camel).joinToString(" ") { it.value }
}
