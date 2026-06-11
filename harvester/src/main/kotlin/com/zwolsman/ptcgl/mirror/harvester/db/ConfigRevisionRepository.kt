package com.zwolsman.ptcgl.mirror.harvester.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class ConfigRevisionRepository(private val jdbc: JdbcTemplate) {

    /** Returns the last stored revision for [docId], or null if never fetched. */
    fun find(docId: String): String? =
        jdbc.query(
            "SELECT revision FROM config_revision WHERE doc_id = ?",
            { rs, _ -> rs.getString("revision") },
            docId
        ).firstOrNull()

    /** Upsert the revision for [docId]. */
    @Transactional
    fun save(docId: String, revision: String) {
        jdbc.update("""
            INSERT INTO config_revision (doc_id, revision, fetched_at)
            VALUES (?, ?, now())
            ON CONFLICT (doc_id) DO UPDATE SET
                revision   = EXCLUDED.revision,
                fetched_at = EXCLUDED.fetched_at
        """.trimIndent(), docId, revision)
    }

    /** Returns true if the stored revision matches [revision] (no change). */
    fun isUpToDate(docId: String, revision: String): Boolean =
        find(docId) == revision
}
