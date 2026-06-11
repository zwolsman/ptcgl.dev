package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.zwolsman.ptcgl.mirror.harvester.domain.CardLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardRecord
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec

/**
 * Maps a decoded DataTable (from card-database-{set}_{locale}_0.0) to domain records.
 *
 * Column mapping verified against the live SV1_EN fixture (63 columns, 444 rows):
 *   cardID              → CardRecord.id
 *   setCode (lowered)   → CardRecord.setId
 *   CompSea Card Number → CardRecord.number  (formatted, e.g. "001")
 *   Loc Rarity Code     → CardRecord.rarity
 *   Regulations symbol  → CardRecord.regulationMark
 *   archetypeID         → CardRecord.archetype
 *   HP                  → CardRecord.hp
 *   EN Type             → CardRecord.types  (single-letter type codes, split on "/")
 *   EN Evolves From     → CardRecord.evolvesFrom
 *   Group ID            → CardRecord.groupId
 *   EN Card Name        → CardLocalizationRecord.name
 */
object CardDbNormalizer {

    data class NormalizeResult(
        val cards: List<CardRecord>,
        val localizations: List<CardLocalizationRecord>,
    )

    fun normalize(table: DataTableCodec.Table, locale: String): NormalizeResult {
        val cards = mutableListOf<CardRecord>()
        val localizations = mutableListOf<CardLocalizationRecord>()

        for (row in table.rows) {
            val cardId = row.str("cardID") ?: continue

            val setCode = row.str("setCode")?.lowercase() ?: cardId.substringBeforeLast("_")
            val number  = row.str("CompSea Card Number") ?: row.str("EN Card #") ?: ""
            val rarity  = row.str("Loc Rarity Code")?.takeIf { it.isNotBlank() }
            val regMark = row.str("Regulations symbol")?.takeIf { it.isNotBlank() }
            val arch    = row.str("archetypeID")?.takeIf { it.isNotBlank() }
            val hp      = row.int("HP")
            val types   = row.str("EN Type")?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
            val evo     = row.str("EN Evolves From")?.takeIf { it.isNotBlank() }
            val group   = row.str("Group ID")?.takeIf { it.isNotBlank() }
            val name    = row.str("EN Card Name") ?: row.str("LocalizedCardName") ?: ""

            cards += CardRecord(cardId, setCode, number, rarity, regMark, arch, hp, types, evo, group)
            localizations += CardLocalizationRecord(cardId, locale, name)
        }

        return NormalizeResult(cards, localizations)
    }

    private fun Map<String, Any?>.str(key: String): String? =
        this[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    private fun Map<String, Any?>.int(key: String): Int? =
        when (val v = this[key]) {
            is Int -> v
            is String -> v.toIntOrNull()
            else -> null
        }
}
