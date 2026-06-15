package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.AttackLocalization
import com.zwolsman.ptcgl.mirror.api.model.AttackResponse
import com.zwolsman.ptcgl.mirror.api.model.CardAssets
import com.zwolsman.ptcgl.mirror.api.model.CardResponse
import com.zwolsman.ptcgl.mirror.api.model.Resistance
import com.zwolsman.ptcgl.mirror.api.model.Weakness
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class CardQueryRepository(
    private val jdbc: JdbcTemplate,
    @param:Value("\${mirror.api.asset-base-url}") private val assetBaseUrl: String,
) {

    fun findBySetId(setId: String, locale: String): List<CardResponse> {
        val cards = jdbc.query(
            "SELECT * FROM card WHERE set_id = ? ORDER BY number",
            { rs, _ -> rs.toCardRow() },
            setId,
        )
        return enrichCards(cards, locale)
    }

    fun findById(id: String, locale: String): CardResponse? {
        val card = jdbc.query(
            "SELECT * FROM card WHERE id = ?",
            { rs, _ -> rs.toCardRow() },
            id,
        ).firstOrNull() ?: return null
        return enrichCards(listOf(card), locale).firstOrNull()
    }

    private fun enrichCards(cards: List<CardRow>, locale: String): List<CardResponse> {
        if (cards.isEmpty()) return emptyList()
        val ids = cards.map { it.id }

        val locs = queryByIds<Triple<String, String, String>>(
            "SELECT card_id, locale, name FROM card_localization WHERE card_id = ANY(?)",
            ids,
        ) { rs, _ -> Triple(rs.getString("card_id"), rs.getString("locale"), rs.getString("name")) }
            .groupBy({ it.first }, { it.second to it.third })

        val attacks = queryByIds<AttackRow>(
            "SELECT card_id, slot, cost, damage FROM card_attack WHERE card_id = ANY(?) ORDER BY card_id, slot",
            ids,
        ) { rs, _ ->
            AttackRow(
                cardId = rs.getString("card_id"),
                slot   = rs.getInt("slot"),
                cost   = rs.getString("cost"),
                damage = rs.getString("damage"),
            )
        }.groupBy { it.cardId }

        val attackLocs = queryByIds<AttackLocRow>(
            "SELECT card_id, slot, locale, name, text FROM card_attack_localization WHERE card_id = ANY(?)",
            ids,
        ) { rs, _ ->
            AttackLocRow(
                cardId = rs.getString("card_id"),
                slot   = rs.getInt("slot"),
                locale = rs.getString("locale"),
                name   = rs.getString("name"),
                text   = rs.getString("text"),
            )
        }.groupBy { "${it.cardId}:${it.slot}" }

        // Asset names embed the locale: {setId}_{locale}_{number}[_t]
        val hiresNames = cards.associate { "${it.setId}_${locale}_${it.number}" to it.id }
        val thumbNames = cards.associate { "${it.setId}_${locale}_${it.number}_t" to it.id }
        val allAssetNames = (hiresNames.keys + thumbNames.keys).toList()

        val assetByName = queryByIds<AssetRow>(
            "SELECT asset_name, s3_key_decoded FROM asset_object WHERE asset_name = ANY(?) AND s3_key_decoded IS NOT NULL",
            allAssetNames,
        ) { rs, _ ->
            AssetRow(
                assetName     = rs.getString("asset_name"),
                s3KeyDecoded  = rs.getString("s3_key_decoded"),
            )
        }.associateBy { it.assetName }

        return cards.map { c ->
            val hiresName = "${c.setId}_${locale}_${c.number}"
            val thumbName = "${c.setId}_${locale}_${c.number}_t"

            CardResponse(
                id             = c.id,
                setId          = c.setId,
                number         = c.number,
                category       = categoryName(c.category),
                rarity         = c.rarity,
                regulationMark = c.regulationMark,
                archetype      = c.archetype,
                hp             = c.hp,
                types          = c.types,
                evolvesFrom    = c.evolvesFrom,
                retreat        = c.retreat,
                weakness       = c.weaknessType?.let { Weakness(it, c.weaknessAmount ?: "") },
                resistance     = c.resistanceType?.let { Resistance(it, c.resistanceAmount ?: "") },
                localizations  = locs[c.id]?.toMap() ?: emptyMap(),
                attacks        = (attacks[c.id] ?: emptyList()).map { a ->
                    AttackResponse(
                        slot          = a.slot,
                        cost          = a.cost,
                        damage        = a.damage,
                        localizations = (attackLocs["${c.id}:${a.slot}"] ?: emptyList())
                            .associate { al -> al.locale to AttackLocalization(al.name, al.text) },
                    )
                },
                assets = CardAssets(
                    hires = assetByName[hiresName]?.let { assetUrl(hiresName, it.s3KeyDecoded) },
                    thumb = assetByName[thumbName]?.let { assetUrl(thumbName, it.s3KeyDecoded) },
                ),
            )
        }
    }

    /** Public URL: {assetBaseUrl}/{s3KeyDecoded}/{assetName}.png */
    private fun assetUrl(assetName: String, s3KeyDecoded: String) =
        "$assetBaseUrl/$s3KeyDecoded/$assetName.png"

    private fun categoryName(category: Int?) = when (category) {
        1    -> "POKEMON"
        2    -> "TRAINER"
        3    -> "ENERGY"
        else -> null
    }

    private fun ResultSet.toCardRow() = CardRow(
        id               = getString("id"),
        setId            = getString("set_id"),
        number           = getString("number"),
        rarity           = getString("rarity"),
        regulationMark   = getString("regulation_mark"),
        archetype        = getString("archetype"),
        hp               = intOrNull("hp"),
        types            = (getArray("types")?.array as? Array<*>)?.filterIsInstance<String>() ?: emptyList(),
        evolvesFrom      = getString("evolves_from"),
        retreat          = intOrNull("retreat"),
        weaknessType     = getString("weakness_type"),
        weaknessAmount   = getString("weakness_amount"),
        resistanceType   = getString("resistance_type"),
        resistanceAmount = getString("resistance_amount"),
        category         = (getObject("category") as? Number)?.toInt(),
    )

    private fun ResultSet.intOrNull(col: String): Int? {
        val v = getInt(col)
        return if (wasNull()) null else v
    }

    private fun <T> queryByIds(sql: String, ids: List<String>, mapper: (ResultSet, Int) -> T): List<T> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.execute(ConnectionCallback { conn ->
            val arr = conn.createArrayOf("text", ids.toTypedArray())
            conn.prepareStatement(sql).use { ps ->
                ps.setArray(1, arr)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<T>()
                    var row = 0
                    while (rs.next()) result += mapper(rs, row++)
                    result
                }
            }
        })!!
    }

    private data class CardRow(
        val id: String, val setId: String, val number: String,
        val rarity: String?, val regulationMark: String?, val archetype: String?,
        val hp: Int?, val types: List<String>, val evolvesFrom: String?,
        val retreat: Int?, val weaknessType: String?, val weaknessAmount: String?,
        val resistanceType: String?, val resistanceAmount: String?, val category: Int?,
    )

    private data class AttackRow(val cardId: String, val slot: Int, val cost: String?, val damage: String?)

    private data class AttackLocRow(val cardId: String, val slot: Int, val locale: String, val name: String, val text: String?)

    private data class AssetRow(val assetName: String, val s3KeyDecoded: String)
}
