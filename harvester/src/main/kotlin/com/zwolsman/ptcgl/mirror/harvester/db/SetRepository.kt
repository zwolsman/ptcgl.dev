package com.zwolsman.ptcgl.mirror.harvester.db

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
            INSERT INTO set (id, code, name, series, release_date, revision)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                code         = EXCLUDED.code,
                name         = EXCLUDED.name,
                series       = EXCLUDED.series,
                release_date = EXCLUDED.release_date,
                revision     = EXCLUDED.revision
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
            }
            override fun getBatchSize() = sets.size
        })
    }

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
}
