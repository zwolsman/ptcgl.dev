package com.zwolsman.ptcgl.mirror.api.db

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
            """
            SELECT c.*, s.series, s.main_set_count, r.display_name AS rarity_display
            FROM card c
            LEFT JOIN "set" s ON s.id = c.set_id
            LEFT JOIN rarity r ON r.code = c.rarity
            WHERE c.set_id = ? AND c.id ~ '_[0-9]+$'
            ORDER BY c.number
            """.trimIndent(),
            { rs, _ -> rs.toCardRow() },
            setId,
        )
        return enrichCards(cards, locale)
    }

    fun findById(id: String, locale: String): CardResponse? {
        val card = jdbc.query(
            """
            SELECT c.*, s.series, s.main_set_count, r.display_name AS rarity_display
            FROM card c
            LEFT JOIN "set" s ON s.id = c.set_id
            LEFT JOIN rarity r ON r.code = c.rarity
            WHERE c.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toCardRow() },
            id,
        ).firstOrNull() ?: return null
        return enrichCards(listOf(card), locale).firstOrNull()
    }

    private fun enrichCards(cards: List<CardRow>, locale: String): List<CardResponse> {
        if (cards.isEmpty()) return emptyList()
        val ids = cards.map { it.id }

        val names = queryByIds<Pair<String, String>>(
            "SELECT card_id, name FROM card_localization WHERE locale = ? AND card_id = ANY(?)",
            ids,
            locale,
        ) { rs, _ -> rs.getString("card_id") to rs.getString("name") }
            .toMap()

        val attacks = queryByIds<AttackRow>(
            """
            SELECT ca.card_id, ca.slot, ca.cost, ca.damage, cal.name, cal.text
            FROM card_attack ca
            LEFT JOIN card_attack_localization cal
                ON cal.card_id = ca.card_id AND cal.slot = ca.slot AND cal.locale = ?
            WHERE ca.card_id = ANY(?)
            ORDER BY ca.card_id, ca.slot
            """.trimIndent(),
            ids,
            locale,
        ) { rs, _ ->
            AttackRow(
                cardId = rs.getString("card_id"),
                slot   = rs.getInt("slot"),
                name   = rs.getString("name"),
                cost   = rs.getString("cost"),
                damage = rs.getString("damage"),
                text   = rs.getString("text"),
            )
        }.groupBy { it.cardId }

        // variants + otherPrints: all cards sharing the same non-null archetype
        val archetypes = cards.mapNotNull { it.archetype }.distinct()
        val archetypeRows: Map<String, List<ArchetypeRow>> = if (archetypes.isEmpty()) emptyMap() else {
            queryByIds<ArchetypeRow>(
                "SELECT archetype, id, set_id, number FROM card WHERE archetype = ANY(?) ORDER BY id",
                archetypes,
            ) { rs, _ ->
                ArchetypeRow(
                    archetype = rs.getString("archetype"),
                    id        = rs.getString("id"),
                    setId     = rs.getString("set_id"),
                    number    = rs.getString("number"),
                )
            }.groupBy { it.archetype }
        }

        // Asset names embed the locale: {setId}_{locale}_{number}[_t]
        val hiresNames = cards.associate { "${it.setId}_${locale}_${it.number}" to it.id }
        val thumbNames = cards.associate { "${it.setId}_${locale}_${it.number}_t" to it.id }
        val allAssetNames = (hiresNames.keys + thumbNames.keys).toList()

        val assetByName = queryByIds<AssetRow>(
            "SELECT asset_name, s3_key_decoded FROM asset_object WHERE asset_name = ANY(?) AND s3_key_decoded IS NOT NULL",
            allAssetNames,
        ) { rs, _ ->
            AssetRow(
                assetName    = rs.getString("asset_name"),
                s3KeyDecoded = rs.getString("s3_key_decoded"),
            )
        }.associateBy { it.assetName }

        return cards.map { c ->
            val formattedNumber = c.number.toIntOrNull()?.toString() ?: c.number
            val position = c.mainSetCount?.let { "$formattedNumber / $it" }
            val hiresName = "${c.setId}_${locale}_${c.number}"
            val thumbName = "${c.setId}_${locale}_${c.number}_t"
            val siblings = c.archetype?.let { archetypeRows[it] } ?: emptyList()
            val variants = siblings
                .filter { it.setId == c.setId && it.number == c.number && it.id != c.id }
                .map { it.id }
            val otherPrints = siblings
                .filter { (it.setId != c.setId || it.number != c.number) && it.id != c.id && it.id.substringAfterLast('_').all(Char::isDigit) }
                .map { it.id }

            CardResponse(
                id             = c.id,
                setId          = c.setId,
                series         = c.series,
                number         = formattedNumber,
                position       = position,
                name           = names[c.id],
                category       = categoryName(c.category),
                rarity         = c.rarityDisplay,
                regulationMark = c.regulationMark,
                hp             = c.hp,
                types          = c.types,
                evolvesFrom    = c.evolvesFrom,
                retreat        = c.retreat,
                weakness       = c.weaknessType?.let { Weakness(it, c.weaknessAmount ?: "") },
                resistance     = c.resistanceType?.let { Resistance(it, c.resistanceAmount ?: "") },
                variants       = variants,
                otherPrints    = otherPrints,
                attacks        = (attacks[c.id] ?: emptyList()).map { a ->
                    AttackResponse(
                        slot   = a.slot,
                        name   = a.name,
                        cost   = a.cost,
                        damage = a.damage,
                        text   = a.text,
                    )
                },
                assets = CardAssets(
                    hires = assetByName[hiresName]?.let { assetUrl(hiresName, it.s3KeyDecoded) },
                    thumb = assetByName[thumbName]?.let { assetUrl(thumbName, it.s3KeyDecoded) },
                ),
            )
        }
    }

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
        series           = getString("series"),
        number           = getString("number"),
        rarityDisplay    = getString("rarity_display"),
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
        mainSetCount     = intOrNull("main_set_count"),
    )

    private fun ResultSet.intOrNull(col: String): Int? {
        val v = getInt(col)
        return if (wasNull()) null else v
    }

    /**
     * Variant for queries with an extra scalar parameter before the array (e.g. locale).
     * The array placeholder must be the LAST parameter in the SQL.
     */
    private fun <T> queryByIds(sql: String, ids: List<String>, scalarParam: String, mapper: (ResultSet, Int) -> T): List<T> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.execute(ConnectionCallback { conn ->
            val arr = conn.createArrayOf("text", ids.toTypedArray())
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, scalarParam)
                ps.setArray(2, arr)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<T>()
                    var row = 0
                    while (rs.next()) result += mapper(rs, row++)
                    result
                }
            }
        })!!
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
        val id: String, val setId: String, val series: String?, val number: String,
        val rarityDisplay: String?, val regulationMark: String?, val archetype: String?,
        val hp: Int?, val types: List<String>, val evolvesFrom: String?,
        val retreat: Int?, val weaknessType: String?, val weaknessAmount: String?,
        val resistanceType: String?, val resistanceAmount: String?, val category: Int?,
        val mainSetCount: Int?,
    )

    private data class AttackRow(
        val cardId: String, val slot: Int,
        val name: String?, val cost: String?, val damage: String?, val text: String?,
    )

    private data class ArchetypeRow(val archetype: String, val id: String, val setId: String, val number: String)

    private data class AssetRow(val assetName: String, val s3KeyDecoded: String)
}
