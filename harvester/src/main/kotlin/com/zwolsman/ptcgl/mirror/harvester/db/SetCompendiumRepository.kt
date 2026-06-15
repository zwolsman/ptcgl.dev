package com.zwolsman.ptcgl.mirror.harvester.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SetCompendiumRepository(private val jdbc: JdbcTemplate) {

    fun upsert(setId: String, data: CompendiumRow) {
        jdbc.update(
            """
            INSERT INTO set_compendium (set_id, raw_json, total_cards, revision)
            VALUES (?, ?::jsonb, ?, ?)
            ON CONFLICT (set_id) DO UPDATE
                SET raw_json    = EXCLUDED.raw_json,
                    total_cards = EXCLUDED.total_cards,
                    revision    = EXCLUDED.revision,
                    fetched_at  = now()
            """.trimIndent(),
            setId, data.rawJson, data.totalCards, data.revision,
        )
    }

    data class CompendiumRow(
        val rawJson: String,
        val totalCards: Int?,
        val revision: String,
    )
}
