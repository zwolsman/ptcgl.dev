package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.SetResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class SetQueryRepository(private val jdbc: JdbcTemplate) {

    fun findAll(): List<SetResponse> {
        val sets = jdbc.query(
            """
            SELECT id, code, series, release_date, main_set_count, master_set_count
            FROM "set"
            ORDER BY release_date DESC NULLS LAST
            """.trimIndent()
        ) { rs, _ -> rs.toSetRow() }
        if (sets.isEmpty()) return emptyList()

        val locs = jdbc.query(
            "SELECT set_id, locale, name FROM set_localization"
        ) { rs, _ ->
            Triple(rs.getString("set_id"), rs.getString("locale"), rs.getString("name"))
        }.groupBy({ it.first }, { it.second to it.third })

        return sets.map { s ->
            SetResponse(
                id             = s.id,
                code           = s.code,
                series         = s.series,
                releaseDate    = s.releaseDate,
                mainSetCount   = s.mainSetCount,
                masterSetCount = s.masterSetCount,
                localizations  = locs[s.id]?.toMap() ?: emptyMap(),
            )
        }
    }

    fun findById(id: String): SetResponse? {
        val set = jdbc.query(
            """
            SELECT id, code, series, release_date, main_set_count, master_set_count
            FROM "set"
            WHERE id = ?
            """.trimIndent(),
            { rs, _ -> rs.toSetRow() },
            id,
        ).firstOrNull() ?: return null

        val locs = jdbc.query(
            "SELECT locale, name FROM set_localization WHERE set_id = ?",
            { rs, _ -> rs.getString("locale") to rs.getString("name") },
            id,
        ).toMap()

        return SetResponse(
            id             = set.id,
            code           = set.code,
            series         = set.series,
            releaseDate    = set.releaseDate,
            mainSetCount   = set.mainSetCount,
            masterSetCount = set.masterSetCount,
            localizations  = locs,
        )
    }

    private fun ResultSet.toSetRow() = SetRow(
        id             = getString("id"),
        code           = getString("code"),
        series         = getString("series"),
        releaseDate    = getObject("release_date", LocalDate::class.java),
        mainSetCount   = getInt("main_set_count").takeUnless { wasNull() },
        masterSetCount = getInt("master_set_count").takeUnless { wasNull() },
    )

    private data class SetRow(
        val id: String,
        val code: String,
        val series: String?,
        val releaseDate: LocalDate?,
        val mainSetCount: Int?,
        val masterSetCount: Int?,
    )
}
