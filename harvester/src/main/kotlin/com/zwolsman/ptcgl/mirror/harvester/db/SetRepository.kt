package com.zwolsman.ptcgl.mirror.harvester.db

import com.zwolsman.ptcgl.mirror.harvester.domain.SeriesLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.SetLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.SetRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.Types

@Repository
class SetRepository(private val jdbc: JdbcTemplate) {

    @Transactional
    fun upsertSets(sets: List<SetRecord>) {
        if (sets.isEmpty()) return
        val sql = """
            INSERT INTO "set" (id, code, name, series, release_date, revision, main_set_count, master_set_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                code            = EXCLUDED.code,
                name            = EXCLUDED.name,
                series          = EXCLUDED.series,
                release_date    = EXCLUDED.release_date,
                revision        = EXCLUDED.revision,
                main_set_count  = EXCLUDED.main_set_count,
                master_set_count = EXCLUDED.master_set_count
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val s = sets[i]
                ps.setString(1, s.id)
                ps.setString(2, s.code)
                ps.setString(3, s.name)
                ps.setString(4, s.series)
                if (s.releaseDate != null) ps.setObject(5, s.releaseDate) else ps.setNull(5, Types.DATE)
                ps.setString(6, s.revision)
                if (s.mainSetCount != null) ps.setInt(7, s.mainSetCount) else ps.setNull(7, Types.INTEGER)
                if (s.masterSetCount != null) ps.setInt(8, s.masterSetCount) else ps.setNull(8, Types.INTEGER)
            }
            override fun getBatchSize() = sets.size
        })
    }

    /** Returns all set IDs currently in the database. */
    fun findAllSetIds(): List<String> =
        jdbc.query("SELECT id FROM set ORDER BY id") { rs, _ -> rs.getString("id") }

    @Transactional
    fun upsertLocalizations(rows: List<SetLocalizationRecord>) {
        if (rows.isEmpty()) return
        val sql = """
            INSERT INTO set_localization (set_id, locale, name)
            VALUES (?, ?, ?)
            ON CONFLICT (set_id, locale) DO UPDATE SET
                name = EXCLUDED.name
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                ps.setString(1, rows[i].setId)
                ps.setString(2, rows[i].locale)
                ps.setString(3, rows[i].name)
            }
            override fun getBatchSize() = rows.size
        })
    }

    @Transactional
    fun upsertSeriesLocalizations(rows: List<SeriesLocalizationRecord>) {
        if (rows.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO series_localization (series_id, locale, name)
            VALUES (?, ?, ?)
            ON CONFLICT (series_id, locale) DO UPDATE SET
                name = EXCLUDED.name
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    ps.setString(1, rows[i].seriesId)
                    ps.setString(2, rows[i].locale)
                    ps.setString(3, rows[i].name)
                }
                override fun getBatchSize() = rows.size
            },
        )
    }
}
