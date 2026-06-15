package com.zwolsman.ptcgl.mirror.harvester.db

import com.zwolsman.ptcgl.mirror.harvester.normalize.RarityDefinition
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement

@Repository
class RarityRepository(private val jdbc: JdbcTemplate) {

    @Transactional
    fun upsert(rarities: List<RarityDefinition>) {
        if (rarities.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO rarity (code, display_name, sort_value)
            VALUES (?, ?, ?)
            ON CONFLICT (code) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                sort_value   = EXCLUDED.sort_value
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    ps.setString(1, rarities[i].code)
                    ps.setString(2, rarities[i].displayName)
                    ps.setInt(3, rarities[i].sortValue)
                }
                override fun getBatchSize() = rarities.size
            },
        )
    }
}
