package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.zwolsman.ptcgl.mirror.harvester.domain.CardAttackLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardAttackRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardRecord
import com.zwolsman.ptcgl.mirror.rainier.codec.DataTableCodec

/**
 * Maps a decoded DataTable (from card-database-{set}_{locale}_0.0) to domain records.
 *
 * Column mapping verified against the live SV1_EN fixture (63 columns, 444 rows).
 *
 * Locale handling
 * ---------------
 * Verified live against EN and FR fixtures. Key findings:
 *
 * - Non-EN DataTables have MORE columns than EN. FR adds 9 locale-specific columns.
 * - Card name: a generic "LocalizedCardName" column exists in all locales and always
 *   carries the locale-specific value (e.g. "Pomdepik" in FR). Fall back to "EN Card Name".
 * - Attack name/text: locale-specific columns are prefixed with the uppercase locale code,
 *   e.g. "FR Attack Name", "FR Attack Name 2", "FR Attack Text". There is NO generic
 *   "LocalizedAttackName" column — that assumption was wrong.
 *   Pattern: "{LOCALE} Attack Name" (slot 1) / "{LOCALE} Attack Name {N}" (slots 2-4).
 *   For EN locale the prefix is "EN", which is identical to the always-English column, so
 *   the same logic works without a special case.
 * - Locale-invariant fields (cost, damage, type codes, HP, retreat, weakness, resistance,
 *   rarity, category, …): confirmed identical between EN and FR — always read from the
 *   deterministic EN/plain column.
 */
object CardDbNormalizer {

    data class NormalizeResult(
        val cards: List<CardRecord>,
        val localizations: List<CardLocalizationRecord>,
        val attacks: List<CardAttackRecord>,
        val attackLocalizations: List<CardAttackLocalizationRecord>,
    )

    // Attack slot column layout. Slot 1 has no numeric suffix; slots 2-4 append " 2/3/4".
    // attackType is 1-indexed; attackID is 0-indexed (DataTable naming is inconsistent).
    // Locale-specific attack columns use the uppercase locale code as prefix, e.g. "FR Attack Name".
    // For EN locale the prefix "EN" matches the always-English column, so no special case needed.
    private data class SlotCols(
        val nameEn: String,
        val textEn: String,
        val cost: String,
        val damage: String,
        val attackType: String,
        val attackId: String,
    )

    private val SLOT_COLS = listOf(
        SlotCols("EN Attack Name",   "EN Attack Text",   "EN Cost",   "Damage",   "attackType1", "attackID0"),
        SlotCols("EN Attack Name 2", "EN Attack Text 2", "EN Cost 2", "Damage 2", "attackType2", "attackID1"),
        SlotCols("EN Attack Name 3", "EN Attack Text 3", "EN Cost 3", "Damage 3", "attackType3", "attackID2"),
        SlotCols("EN Attack Name 4", "EN Attack Text 4", "EN Cost 4", "Damage 4", "attackType4", "attackID3"),
    )

    // "{LOCALE} Attack Name" (slot 1) / "{LOCALE} Attack Name {N}" (slots 2-4).
    // Falls back to the EN column when the locale column is absent (e.g. future/unknown locales).
    private fun localizedAttackName(locale: String, slot: Int): String {
        val prefix = locale.uppercase()
        return if (slot == 1) "$prefix Attack Name" else "$prefix Attack Name $slot"
    }

    private fun localizedAttackText(locale: String, slot: Int): String {
        val prefix = locale.uppercase()
        return if (slot == 1) "$prefix Attack Text" else "$prefix Attack Text $slot"
    }

    fun normalize(table: DataTableCodec.Table, locale: String): NormalizeResult {
        val cards = mutableListOf<CardRecord>()
        val localizations = mutableListOf<CardLocalizationRecord>()
        val attacks = mutableListOf<CardAttackRecord>()
        val attackLocalizations = mutableListOf<CardAttackLocalizationRecord>()

        for (row in table.rows) {
            val cardId = row.str("cardID") ?: continue

            val setCode = row.str("setCode")?.lowercase() ?: cardId.substringBeforeLast("_")
            val number  = row.str("CompSea Card Number") ?: row.str("EN Card #") ?: ""

            cards += CardRecord(
                id               = cardId,
                setId            = setCode,
                number           = number,
                rarity           = row.str("Loc Rarity Code"),
                regulationMark   = row.str("Regulations symbol"),
                archetype        = row.str("archetypeID"),
                hp               = row.int("HP")?.takeIf { it > 0 },
                types            = row.str("EN Type")?.split("/")?.filter { it.isNotBlank() } ?: emptyList(),
                evolvesFrom      = row.str("EN Evolves From"),
                groupId          = row.str("Group ID"),
                retreat          = row.int("Retreat"),
                weaknessType     = row.str("EN Weakness Type"),
                weaknessAmount   = row.str("Weakness Amount"),
                resistanceType   = row.str("EN Resistance Type"),
                resistanceAmount = row.str("Resistance Amount"),
                category         = row.int("category"),
            )

            // Prefer LocalizedCardName (locale-specific) over EN Card Name (always English).
            val name = row.str("LocalizedCardName") ?: row.str("EN Card Name") ?: ""

            // Trainer/energy card effect: collect text from every slot that has no attack name,
            // joining multiple sections with newline. Pokémon slots with a name are skipped.
            val bodyText = SLOT_COLS.mapIndexed { idx, cols ->
                val slot = idx + 1
                val slotName = row.str(localizedAttackName(locale, slot)) ?: row.str(cols.nameEn)
                if (slotName == null) row.str(localizedAttackText(locale, slot)) ?: row.str(cols.textEn) else null
            }.filterNotNull().joinToString("\n").takeIf { it.isNotBlank() }

            localizations += CardLocalizationRecord(cardId, locale, name, bodyText)

            // Emit only populated attack slots (non-blank name).
            // Prefer the locale-prefixed column (e.g. "FR Attack Name"); fall back to "EN Attack Name".
            // For EN locale both resolve to the same column, so no special-casing needed.
            SLOT_COLS.forEachIndexed { idx, cols ->
                val slot = idx + 1
                val attackName = row.str(localizedAttackName(locale, slot))
                    ?: row.str(cols.nameEn)
                    ?: return@forEachIndexed
                attacks += CardAttackRecord(
                    cardId     = cardId,
                    slot       = slot,
                    cost       = row.str(cols.cost),
                    damage     = row.str(cols.damage),
                    attackType = row.int(cols.attackType),
                    attackId   = row.int(cols.attackId),
                )
                attackLocalizations += CardAttackLocalizationRecord(
                    cardId = cardId,
                    slot   = slot,
                    locale = locale,
                    name   = attackName,
                    text   = row.str(localizedAttackText(locale, slot)) ?: row.str(cols.textEn),
                )
            }
        }

        return NormalizeResult(cards, localizations, attacks, attackLocalizations)
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
