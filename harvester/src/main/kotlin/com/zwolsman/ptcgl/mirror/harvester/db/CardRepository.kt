package com.zwolsman.ptcgl.mirror.harvester.db

import com.zwolsman.ptcgl.mirror.harvester.domain.CardAttackLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardAttackRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardLocalizationRecord
import com.zwolsman.ptcgl.mirror.harvester.domain.CardRecord
import org.springframework.jdbc.core.ConnectionCallback
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
            INSERT INTO card (id, set_id, number, rarity, regulation_mark, archetype, hp, types,
                              evolves_from, group_id, retreat, weakness_type, weakness_amount,
                              resistance_type, resistance_amount, category)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                set_id           = EXCLUDED.set_id,
                number           = EXCLUDED.number,
                rarity           = EXCLUDED.rarity,
                regulation_mark  = EXCLUDED.regulation_mark,
                archetype        = EXCLUDED.archetype,
                hp               = EXCLUDED.hp,
                types            = EXCLUDED.types,
                evolves_from     = EXCLUDED.evolves_from,
                group_id         = EXCLUDED.group_id,
                retreat          = EXCLUDED.retreat,
                weakness_type    = EXCLUDED.weakness_type,
                weakness_amount  = EXCLUDED.weakness_amount,
                resistance_type  = EXCLUDED.resistance_type,
                resistance_amount = EXCLUDED.resistance_amount,
                category         = EXCLUDED.category
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
                    if (c.retreat != null) ps.setInt(11, c.retreat) else ps.setNull(11, Types.INTEGER)
                    ps.setString(12, c.weaknessType)
                    ps.setString(13, c.weaknessAmount)
                    ps.setString(14, c.resistanceType)
                    ps.setString(15, c.resistanceAmount)
                    if (c.category != null) ps.setShort(16, c.category.toShort()) else ps.setNull(16, Types.SMALLINT)
                }
                override fun getBatchSize() = cards.size
            })
        } finally {
            conn.close()
        }
    }

    @Transactional
    fun upsertLocalizations(rows: List<CardLocalizationRecord>) {
        if (rows.isEmpty()) return
        val sql = """
            INSERT INTO card_localization (card_id, locale, name, text)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (card_id, locale) DO UPDATE SET
                name = EXCLUDED.name,
                text = EXCLUDED.text
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                ps.setString(1, rows[i].cardId)
                ps.setString(2, rows[i].locale)
                ps.setString(3, rows[i].name)
                ps.setString(4, rows[i].text)
            }
            override fun getBatchSize() = rows.size
        })
    }

    // Must be called before upsertAttackLocalizations (FK constraint).
    @Transactional
    fun upsertAttacks(attacks: List<CardAttackRecord>) {
        if (attacks.isEmpty()) return
        val sql = """
            INSERT INTO card_attack (card_id, slot, cost, damage, attack_type, attack_id)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (card_id, slot) DO UPDATE SET
                cost        = EXCLUDED.cost,
                damage      = EXCLUDED.damage,
                attack_type = EXCLUDED.attack_type,
                attack_id   = EXCLUDED.attack_id
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val a = attacks[i]
                ps.setString(1, a.cardId)
                ps.setInt(2, a.slot)
                ps.setString(3, a.cost)
                ps.setString(4, a.damage)
                if (a.attackType != null) ps.setInt(5, a.attackType) else ps.setNull(5, Types.INTEGER)
                if (a.attackId != null) ps.setInt(6, a.attackId) else ps.setNull(6, Types.INTEGER)
            }
            override fun getBatchSize() = attacks.size
        })
    }

    @Transactional
    fun upsertAttackLocalizations(rows: List<CardAttackLocalizationRecord>) {
        if (rows.isEmpty()) return
        val sql = """
            INSERT INTO card_attack_localization (card_id, slot, locale, name, text)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (card_id, slot, locale) DO UPDATE SET
                name = EXCLUDED.name,
                text = EXCLUDED.text
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val r = rows[i]
                ps.setString(1, r.cardId)
                ps.setInt(2, r.slot)
                ps.setString(3, r.locale)
                ps.setString(4, r.name)
                ps.setString(5, r.text)
            }
            override fun getBatchSize() = rows.size
        })
    }

    /** Returns unique (set_id, number) pairs for cards in the given sets, or all sets if null. */
    fun findDistinctCardKeys(setIds: List<String>? = null): List<Pair<String, String>> {
        if (setIds.isNullOrEmpty()) {
            return jdbc.query("SELECT DISTINCT set_id, number FROM card ORDER BY set_id, number") { rs, _ ->
                rs.getString("set_id") to rs.getString("number")
            }
        }
        return jdbc.execute(ConnectionCallback { conn ->
            val arr = conn.createArrayOf("text", setIds.toTypedArray())
            conn.prepareStatement(
                "SELECT DISTINCT set_id, number FROM card WHERE set_id = ANY(?) ORDER BY set_id, number"
            ).use { ps ->
                ps.setArray(1, arr)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<Pair<String, String>>()
                    while (rs.next()) result += rs.getString("set_id") to rs.getString("number")
                    result
                }
            }
        })!!
    }
}
