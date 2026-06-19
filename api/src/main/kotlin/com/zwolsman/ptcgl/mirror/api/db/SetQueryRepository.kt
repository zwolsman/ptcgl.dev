package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.SeriesResponse
import com.zwolsman.ptcgl.mirror.api.model.SetResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class SetQueryRepository(
    private val jdbc: JdbcTemplate,
    @param:Value("\${mirror.api.asset-base-url}") private val assetBaseUrl: String,
    @param:Value("\${mirror.api.decoded-s3-prefix}") private val decodedS3Prefix: String,
) {

    fun findAllSeries(): List<SeriesResponse> {
        return jdbc.query(
            """
            SELECT series, COUNT(*) AS set_count
            FROM "set"
            WHERE series IS NOT NULL
            GROUP BY series
            ORDER BY series
            """.trimIndent(),
        ) { rs, _ -> SeriesResponse(rs.getString("series"), rs.getInt("set_count")) }
    }

    fun findAll(locale: String = "en"): List<SetResponse> {
        return jdbc.query(
            """
            SELECT s.id, s.code, s.series, s.release_date, s.main_set_count, s.master_set_count,
                   sl.name, ao.s3_key_decoded AS logo_decoded, ao.texture_name AS logo_texture
            FROM "set" s
            LEFT JOIN set_localization sl ON sl.set_id = s.id AND sl.locale = ?
            LEFT JOIN asset_object ao ON ao.asset_name = 'expansion_' || s.id || '_' || ?
                AND ao.s3_key_decoded IS NOT NULL
            ORDER BY s.release_date DESC NULLS LAST
            """.trimIndent(),
            { rs, _ -> rs.toSetResponse() },
            locale, locale,
        )
    }

    fun findById(id: String, locale: String = "en"): SetResponse? {
        return jdbc.query(
            """
            SELECT s.id, s.code, s.series, s.release_date, s.main_set_count, s.master_set_count,
                   sl.name, ao.s3_key_decoded AS logo_decoded, ao.texture_name AS logo_texture
            FROM "set" s
            LEFT JOIN set_localization sl ON sl.set_id = s.id AND sl.locale = ?
            LEFT JOIN asset_object ao ON ao.asset_name = 'expansion_' || s.id || '_' || ?
                AND ao.s3_key_decoded IS NOT NULL
            WHERE s.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toSetResponse() },
            locale, locale, id,
        ).firstOrNull()
    }

    fun findBySeries(series: String, locale: String = "en"): List<SetResponse> {
        return jdbc.query(
            """
            SELECT s.id, s.code, s.series, s.release_date, s.main_set_count, s.master_set_count,
                   sl.name, ao.s3_key_decoded AS logo_decoded, ao.texture_name AS logo_texture
            FROM "set" s
            LEFT JOIN set_localization sl ON sl.set_id = s.id AND sl.locale = ?
            LEFT JOIN asset_object ao ON ao.asset_name = 'expansion_' || s.id || '_' || ?
                AND ao.s3_key_decoded IS NOT NULL
            WHERE s.series = ?
            ORDER BY s.release_date DESC NULLS LAST
            """.trimIndent(),
            { rs, _ -> rs.toSetResponse() },
            locale, locale, series,
        )
    }

    private fun ResultSet.toSetResponse(): SetResponse {
        val logoDecoded  = getString("logo_decoded")
        val logoTexture  = getString("logo_texture")
        return SetResponse(
            id             = getString("id"),
            series         = getString("series"),
            code           = getString("code"),
            name           = getString("name"),
            releaseDate    = getObject("release_date", LocalDate::class.java),
            mainSetCount   = getInt("main_set_count").takeUnless { wasNull() },
            masterSetCount = getInt("master_set_count").takeUnless { wasNull() },
            logo           = if (logoDecoded != null && logoTexture != null)
                                 "$assetBaseUrl/${logoDecoded.removePrefix(decodedS3Prefix)}/$logoTexture.png"
                             else null,
        )
    }
}
