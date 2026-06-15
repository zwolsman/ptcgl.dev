package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.SetResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class SetQueryRepository(private val jdbc: JdbcTemplate) {

    fun findAll(): List<SetResponse> {
        val sets = jdbc.query(
            """SELECT id, code, series, release_date FROM "set" ORDER BY release_date DESC NULLS LAST"""
        ) { rs, _ ->
            SetRow(
                id          = rs.getString("id"),
                code        = rs.getString("code"),
                series      = rs.getString("series"),
                releaseDate = rs.getObject("release_date", LocalDate::class.java),
            )
        }
        if (sets.isEmpty()) return emptyList()

        val locs = jdbc.query(
            "SELECT set_id, locale, name FROM set_localization"
        ) { rs, _ ->
            Triple(rs.getString("set_id"), rs.getString("locale"), rs.getString("name"))
        }.groupBy({ it.first }, { it.second to it.third })

        return sets.map { s ->
            SetResponse(
                id            = s.id,
                code          = s.code,
                series        = s.series,
                releaseDate   = s.releaseDate,
                localizations = locs[s.id]?.toMap() ?: emptyMap(),
            )
        }
    }

    fun findById(id: String): SetResponse? {
        val set = jdbc.query(
            """SELECT id, code, series, release_date FROM "set" WHERE id = ?"""
        ) { rs, _ ->
            SetRow(
                id          = rs.getString("id"),
                code        = rs.getString("code"),
                series      = rs.getString("series"),
                releaseDate = rs.getObject("release_date", LocalDate::class.java),
            )
        }.firstOrNull() ?: return null

        val locs = jdbc.query(
            "SELECT locale, name FROM set_localization WHERE set_id = ?",
            { rs, _ -> rs.getString("locale") to rs.getString("name") },
            id,
        ).toMap()

        return SetResponse(
            id            = set.id,
            code          = set.code,
            series        = set.series,
            releaseDate   = set.releaseDate,
            localizations = locs,
        )
    }

    private data class SetRow(val id: String, val code: String, val series: String?, val releaseDate: LocalDate?)
}
