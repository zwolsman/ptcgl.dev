package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.SetResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class SetQueryRepository(private val jdbc: JdbcTemplate) {

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

    fun findAllSeries(): List<String> {
        return jdbc.query(
            """SELECT DISTINCT series FROM "set" WHERE series IS NOT NULL ORDER BY series""",
        ) { rs, _ -> rs.getString("series") }
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
        code           = getString("code"),
        series         = getString("series"),
        releaseDate    = getObject("release_date", LocalDate::class.java),
        name           = getString("name"),
        mainSetCount   = getInt("main_set_count").takeUnless { wasNull() },
        masterSetCount = getInt("master_set_count").takeUnless { wasNull() },
    )
}
