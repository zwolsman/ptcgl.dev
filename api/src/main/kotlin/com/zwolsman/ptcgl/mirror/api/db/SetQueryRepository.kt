package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.SeriesResponse
import com.zwolsman.ptcgl.mirror.api.model.SetResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class SetQueryRepository(private val jdbc: JdbcTemplate) {

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
                   sl.name
            FROM "set" s
            LEFT JOIN set_localization sl ON sl.set_id = s.id AND sl.locale = ?
            ORDER BY s.release_date DESC NULLS LAST
            """.trimIndent(),
            { rs, _ -> rs.toSetResponse() },
            locale,
        )
    }

    fun findById(id: String, locale: String = "en"): SetResponse? {
        return jdbc.query(
            """
            SELECT s.id, s.code, s.series, s.release_date, s.main_set_count, s.master_set_count,
                   sl.name
            FROM "set" s
            LEFT JOIN set_localization sl ON sl.set_id = s.id AND sl.locale = ?
            WHERE s.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toSetResponse() },
            locale,
            id,
        ).firstOrNull()
    }

    fun findBySeries(series: String, locale: String = "en"): List<SetResponse> {
        return jdbc.query(
            """
            SELECT s.id, s.code, s.series, s.release_date, s.main_set_count, s.master_set_count,
                   sl.name
            FROM "set" s
            LEFT JOIN set_localization sl ON sl.set_id = s.id AND sl.locale = ?
            WHERE s.series = ?
            ORDER BY s.release_date DESC NULLS LAST
            """.trimIndent(),
            { rs, _ -> rs.toSetResponse() },
            locale,
            series,
        )
    }

    private fun ResultSet.toSetResponse() = SetResponse(
        id             = getString("id"),
        series         = getString("series"),
        code           = getString("code"),
        name           = getString("name"),
        releaseDate    = getObject("release_date", LocalDate::class.java),
        mainSetCount   = getInt("main_set_count").takeUnless { wasNull() },
        masterSetCount = getInt("master_set_count").takeUnless { wasNull() },
    )
}
