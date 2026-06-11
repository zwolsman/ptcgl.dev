package com.zwolsman.ptcgl.mirror.harvester.db

import com.zwolsman.ptcgl.mirror.harvester.domain.CardLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.Types

@Repository
class CardRepository(private val jdbc: JdbcTemplate) {

    @Transactional
    fun upsertCards(cards: List<CardRecord>) {
        if (cards.isEmpty()) return
        val sql = """
            INSERT INTO card (id, set_id, number, rarity, regulation_mark, archetype, hp, types, evolves_from, group_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                set_id          = EXCLUDED.set_id,
                number          = EXCLUDED.number,
                rarity          = EXCLUDED.rarity,
                regulation_mark = EXCLUDED.regulation_mark,
                archetype       = EXCLUDED.archetype,
                hp              = EXCLUDED.hp,
                types           = EXCLUDED.types,
                evolves_from    = EXCLUDED.evolves_from,
                group_id        = EXCLUDED.group_id
        """.trimIndent()
        val conn = jdbc.dataSource!!.connection
        val typesArray = cards.map { c -> conn.createArrayOf("text", c.types.toTypedArray()) }
        try {
            jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val c = cards[i]
                    ps.setString(1, c.id)
                    ps.setString(2, c.setId)
                    ps.setString(3, c.number)
                    ps.setString(4, c.rarity)
                    ps.setString(5, c.regulationMark)
                    ps.setString(6, c.archetype)
                    if (c.hp != null) ps.setInt(7, c.hp) else ps.setNull(7, Types.INTEGER)
                    ps.setArray(8, typesArray[i])
                    ps.setString(9, c.evolvesFrom)
                    ps.setString(10, c.groupId)
                }
                override fun getBatchSize() = cards.size
            })
        } finally {
            conn.close()
        }
    }

    /** Returns unique (set_id, number) pairs for all cards currently in the database. */
    fun findDistinctCardKeys(): List<Pair<String, String>> =
        jdbc.query("SELECT DISTINCT set_id, number FROM card ORDER BY set_id, number") { rs, _ ->
            rs.getString("set_id") to rs.getString("number")
        }

    @Transactional
    fun upsertLocalizations(rows: List<CardLocalizationRecord>) {
        if (rows.isEmpty()) return
        val sql = """
            INSERT INTO card_localization (card_id, locale, name)
            VALUES (?, ?, ?)
            ON CONFLICT (card_id, locale) DO UPDATE SET
                name = EXCLUDED.name
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                ps.setString(1, rows[i].cardId)
                ps.setString(2, rows[i].locale)
                ps.setString(3, rows[i].name)
            }
            override fun getBatchSize() = rows.size
        })
    }
}
