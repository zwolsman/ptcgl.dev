package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.AttackResponse
import com.zwolsman.ptcgl.mirror.api.model.CardAssets
import com.zwolsman.ptcgl.mirror.api.model.CardResponse
import com.zwolsman.ptcgl.mirror.api.model.CardSummaryResponse
import com.zwolsman.ptcgl.mirror.api.model.OtherPrint
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
    @param:Value("\${mirror.api.decoded-s3-prefix}") private val decodedS3Prefix: String,
) {

    fun findByName(name: String, locale: String, exact: Boolean = false): List<CardSummaryResponse> {
        if (name.isBlank()) return emptyList()
        // ILIKE without wildcards = case-insensitive exact match; with % = partial match
        val param = if (exact) name else "%$name%"
        val rows = jdbc.query(
            """
            SELECT c.id, c.set_id, c.number, s.main_set_count, cl.name
              FROM card_localization cl
              JOIN card c ON c.id = cl.card_id
              LEFT JOIN "set" s ON s.id = c.set_id
             WHERE cl.locale = ? AND cl.name ILIKE ?
               AND c.id ~ '_[0-9]+$'
             ORDER BY c.set_id, c.number
            """.trimIndent(),
            { rs, _ ->
                NameSearchRow(
                    id           = rs.getString("id"),
                    setId        = rs.getString("set_id"),
                    number       = rs.getString("number"),
                    mainSetCount = rs.getObject("main_set_count") as? Int,
                    name         = rs.getString("name"),
                )
            },
            locale,
            param,
        )
        if (rows.isEmpty()) return emptyList()

        val thumbAssets = queryByIds<AssetRow>(
            "SELECT asset_name, s3_key_decoded, texture_name FROM asset_object WHERE asset_name = ANY(?) AND s3_key_decoded IS NOT NULL",
            rows.map { cardAssetBaseName(it.id, locale) + "_t" },
        ) { rs, _ ->
            AssetRow(rs.getString("asset_name"), rs.getString("s3_key_decoded"), rs.getString("texture_name"))
        }.associateBy { it.assetName }

        return rows.map { c ->
            val formatted = c.number.toIntOrNull()?.toString() ?: c.number
            val thumbName = cardAssetBaseName(c.id, locale) + "_t"
            CardSummaryResponse(
                id       = c.id,
                number   = formatted,
                position = c.mainSetCount?.let { "$formatted / $it" },
                name     = c.name,
                thumb    = thumbAssets[thumbName]?.let { assetUrl(thumbName, it.s3KeyDecoded, it.textureName) },
            )
        }
    }

    fun findSummariesBySetId(setId: String, locale: String): List<CardSummaryResponse> {
        val cards = jdbc.query(
            """
            SELECT c.id, c.number, s.main_set_count
              FROM card c
              LEFT JOIN "set" s ON s.id = c.set_id
             WHERE split_part(c.id, '_', 1) = ? AND c.id ~ '_[0-9]+$'
             ORDER BY c.number
            """.trimIndent(),
            { rs, _ -> Triple(rs.getString("id"), rs.getString("number"), rs.getObject("main_set_count") as? Int) },
            setId,
        )
        if (cards.isEmpty()) return emptyList()

        val ids = cards.map { it.first }

        val names = queryByIds<Pair<String, String>>(
            "SELECT card_id, name FROM card_localization WHERE locale = ? AND card_id = ANY(?)",
            ids, locale,
        ) { rs, _ -> rs.getString("card_id") to rs.getString("name") }.toMap()

        val thumbAssets = queryByIds<AssetRow>(
            "SELECT asset_name, s3_key_decoded, texture_name FROM asset_object WHERE asset_name = ANY(?) AND s3_key_decoded IS NOT NULL",
            cards.map { (id, _, _) -> cardAssetBaseName(id, locale) + "_t" },
        ) { rs, _ ->
            AssetRow(
                assetName    = rs.getString("asset_name"),
                s3KeyDecoded = rs.getString("s3_key_decoded"),
                textureName  = rs.getString("texture_name"),
            )
        }.associateBy { it.assetName }

        return cards.map { (id, number, mainSetCount) ->
            val formatted = number.toIntOrNull()?.toString() ?: number
            val thumbAssetName = cardAssetBaseName(id, locale) + "_t"
            val thumbAsset = thumbAssets[thumbAssetName]
            CardSummaryResponse(
                id       = id,
                number   = formatted,
                position = mainSetCount?.let { "$formatted / $it" },
                name     = names[id],
                thumb    = thumbAsset?.let { assetUrl(thumbAssetName, it.s3KeyDecoded, it.textureName) },
            )
        }
    }

    fun findBySetId(setId: String, locale: String): List<CardResponse> {
        val cards = jdbc.query(
            """
            SELECT c.*, s.series, s.main_set_count, r.display_name AS rarity_display
            FROM card c
            LEFT JOIN "set" s ON s.id = c.set_id
            LEFT JOIN rarity r ON r.code = c.rarity
            WHERE split_part(c.id, '_', 1) = ? AND c.id ~ '_[0-9]+$'
            ORDER BY c.number
            """.trimIndent(),
            { rs, _ -> rs.toCardRow() },
            setId,
        )
        return enrichCards(cards, locale, thumbOnly = true)
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
        return enrichCards(listOf(card), locale, thumbOnly = false).firstOrNull()
    }

    private fun enrichCards(cards: List<CardRow>, locale: String, thumbOnly: Boolean): List<CardResponse> {
        if (cards.isEmpty()) return emptyList()
        val ids = cards.map { it.id }

        data class LocalizationRow(val cardId: String, val name: String?, val text: String?)
        val localizations = queryByIds<LocalizationRow>(
            "SELECT card_id, name, text FROM card_localization WHERE locale = ? AND card_id = ANY(?)",
            ids,
            locale,
        ) { rs, _ -> LocalizationRow(rs.getString("card_id"), rs.getString("name"), rs.getString("text")) }
        val names = localizations.associate { it.cardId to it.name }
        val bodyTexts = localizations.associate { it.cardId to it.text }

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

        // Collect all asset names we need: thumb for everyone; hires+manifest for detail
        val thumbNames: Map<String, String> = cards.associate { c -> cardAssetBaseName(c.id, locale) + "_t" to c.id }
        val hiresNames: Map<String, String> = if (thumbOnly) emptyMap() else cards.associate { c -> cardAssetBaseName(c.id, locale) to c.id }

        // Gather otherPrint sibling IDs across all cards for a bulk thumb lookup.
        // Derive thumb asset names directly from card IDs — no extra DB query needed.
        val otherPrintSiblingIds: List<String> = archetypeRows.values.flatten()
            .filter { row -> row.id.substringAfterLast('_').all(Char::isDigit) }
            .map { it.id }
            .distinct()

        val siblingThumbAssetNames: Map<String, String> = otherPrintSiblingIds
            .associateWith { id -> cardAssetBaseName(id, locale) + "_t" }

        val allAssetNames = (thumbNames.keys + hiresNames.keys + siblingThumbAssetNames.values).distinct()

        val assetByName = queryByIds<AssetRow>(
            "SELECT asset_name, s3_key_decoded, texture_name FROM asset_object WHERE asset_name = ANY(?) AND s3_key_decoded IS NOT NULL",
            allAssetNames,
        ) { rs, _ ->
            AssetRow(
                assetName    = rs.getString("asset_name"),
                s3KeyDecoded = rs.getString("s3_key_decoded"),
                textureName  = rs.getString("texture_name"),
            )
        }.associateBy { it.assetName }

        // material_manifest grouped by bundle_asset_name; each bundle may have multiple variant rows.
        // Lookup picks the row matching the card's variant suffix, falling back to the first available
        // row so that alt-set bundles (variant_suffix="alt") are found even for base card IDs.
        val manifestsByBundle: Map<String, List<ManifestRow>> = if (thumbOnly || hiresNames.isEmpty()) emptyMap() else {
            queryByIds<ManifestRow>(
                "SELECT bundle_asset_name, variant_suffix, whiteplate_name, etch_name, foil_type, shader_path FROM material_manifest WHERE bundle_asset_name = ANY(?)",
                hiresNames.keys.toList(),
            ) { rs, _ ->
                ManifestRow(
                    bundleAssetName = rs.getString("bundle_asset_name"),
                    variantSuffix   = rs.getString("variant_suffix"),
                    whiteplateName  = rs.getString("whiteplate_name"),
                    etchName        = rs.getString("etch_name"),
                    foilType        = rs.getString("foil_type"),
                )
            }.groupBy { it.bundleAssetName }
        }

        // Reverse-map sibling thumb asset names → their thumb URL
        val siblingThumbUrl: Map<String, String?> = siblingThumbAssetNames.mapValues { (_, thumbAssetName) ->
            assetByName[thumbAssetName]?.let { assetUrl(thumbAssetName, it.s3KeyDecoded, it.textureName) }
        }

        return cards.map { c ->
            val formattedNumber = c.number.toIntOrNull()?.toString() ?: c.number
            val position      = c.mainSetCount?.let { "$formattedNumber / $it" }
            val baseName      = cardAssetBaseName(c.id, locale)
            val thumbName     = "${baseName}_t"
            val hiresName     = baseName
            val variantSuffix = if (c.id.substringAfterLast('_').all(Char::isDigit)) "" else c.id.substringAfterLast('_')
            val hiresAsset    = assetByName[hiresName]
            val thumbAsset    = assetByName[thumbName]
            val bundleManifests = manifestsByBundle[hiresName] ?: emptyList()
            val manifest      = bundleManifests.firstOrNull { it.variantSuffix == variantSuffix }
                ?: if (variantSuffix.isEmpty()) bundleManifests.firstOrNull() else null

            val siblings = c.archetype?.let { archetypeRows[it] } ?: emptyList()
            val variants = siblings
                .filter { it.setId == c.setId && it.number == c.number && it.id != c.id }
                .map { it.id }
            val otherPrints = siblings
                .filter { (it.setId != c.setId || it.number != c.number) && it.id != c.id && it.id.substringAfterLast('_').all(Char::isDigit) }
                .map { sibling -> OtherPrint(id = sibling.id, thumb = siblingThumbUrl[sibling.id]) }

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
                text           = bodyTexts[c.id],
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
                assets = if (thumbOnly) {
                    CardAssets(
                        hires      = null,
                        thumb      = thumbAsset?.let { assetUrl(thumbName, it.s3KeyDecoded, it.textureName) },
                        whiteplate = null,
                        etch       = null,
                        foilType   = null,
                    )
                } else {
                    CardAssets(
                        hires      = hiresAsset?.let { assetUrl(hiresName, it.s3KeyDecoded, it.textureName) },
                        thumb      = thumbAsset?.let { assetUrl(thumbName, it.s3KeyDecoded, it.textureName) },
                        whiteplate = manifest?.whiteplateName?.let { wp -> hiresAsset?.let { a -> assetUrl(wp, a.s3KeyDecoded) } },
                        etch       = manifest?.etchName?.let { e -> hiresAsset?.let { a -> assetUrl(e, a.s3KeyDecoded) } },
                        foilType   = manifest?.foilType?.let { camelToSpaced(it) },
                    )
                },
            )
        }
    }

    private fun assetUrl(assetName: String, s3KeyDecoded: String, textureName: String? = null) =
        "$assetBaseUrl/${s3KeyDecoded.removePrefix(decodedS3Prefix)}/${textureName ?: assetName}.png"

    /**
     * Derives the CDN/S3 asset base name from a card ID.
     * Splits on the first underscore for the set code, then zero-pads the numeric portion to 3 digits.
     *   svalt_1   → svalt_en_001
     *   me4_10_ph → me4_en_010
     *   sv3-5_133 → sv3-5_en_133
     */
    private fun cardAssetBaseName(cardId: String, locale: String): String {
        val sep = cardId.indexOf('_')
        val setCode = if (sep >= 0) cardId.substring(0, sep) else cardId
        val rest    = if (sep >= 0) cardId.substring(sep + 1) else ""
        val number  = rest.substringBefore('_').toIntOrNull() ?: 0
        return "${setCode}_${locale}_%03d".format(number)
    }

    private val camelSplit = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")
    private fun camelToSpaced(s: String) = camelSplit.findAll(s).joinToString(" ") { it.value }

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

    private data class NameSearchRow(
        val id: String, val setId: String, val number: String, val mainSetCount: Int?, val name: String?,
    )

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

    private data class AssetRow(
        val assetName: String,
        val s3KeyDecoded: String,
        val textureName: String?,
    )

    private data class ManifestRow(
        val bundleAssetName: String,
        val variantSuffix: String,
        val whiteplateName: String?,
        val etchName: String?,
        val foilType: String?,
    )
}
