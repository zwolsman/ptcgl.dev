package com.zwolsman.ptcgl.mirror.harvester.db

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.Types

data class AssetLedgerEntry(
    val assetName: String,
    val locale: String,
    val bucket: String,
    val crc: Long?,
    val sourceHash: String?,
)

@Repository
class AssetLedgerRepository(private val jdbc: JdbcTemplate) {

    @Transactional
    fun upsertDesiredAssets(entries: List<AssetLedgerEntry>) {
        if (entries.isEmpty()) return
        val sql = """
            INSERT INTO asset_object (asset_name, locale, bucket, crc, source_hash, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (asset_name, locale) DO UPDATE SET
                bucket       = EXCLUDED.bucket,
                crc          = EXCLUDED.crc,
                source_hash  = EXCLUDED.source_hash,
                status       = CASE
                    WHEN asset_object.status = 'DONE'
                         AND asset_object.source_hash IS NOT DISTINCT FROM EXCLUDED.source_hash
                    THEN 'DONE'
                    WHEN asset_object.status = 'DONE'
                    THEN 'PENDING'
                    ELSE asset_object.status
                END,
                updated_at   = now()
        """.trimIndent()
        jdbc.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val e = entries[i]
                ps.setString(1, e.assetName)
                ps.setString(2, e.locale)
                ps.setString(3, e.bucket)
                if (e.crc != null) ps.setLong(4, e.crc) else ps.setNull(4, Types.BIGINT)
                ps.setString(5, e.sourceHash)
            }
            override fun getBatchSize() = entries.size
        })
    }

    @Transactional
    fun reclaimExpiredLeases() {
        jdbc.update("""
            UPDATE asset_object
               SET status     = 'PENDING',
                   updated_at = now()
             WHERE status = 'IN_PROGRESS'
               AND lease_until < now()
        """.trimIndent())
    }

    @Transactional
    fun markStale(activeAssets: Set<Pair<String, String>>) {
        if (activeAssets.isEmpty()) return
        // Use unnest() to avoid the 65535 bind-parameter limit that NOT IN (?,?) ... hits.
        val names   = activeAssets.map { it.first }.toTypedArray()
        val locales = activeAssets.map { it.second }.toTypedArray()
        jdbc.execute(ConnectionCallback<Unit> { conn ->
            val nameArr   = conn.createArrayOf("text", names)
            val localeArr = conn.createArrayOf("text", locales)
            conn.prepareStatement("""
                UPDATE asset_object
                   SET status     = 'STALE',
                       updated_at = now()
                 WHERE status NOT IN ('STALE', 'DONE')
                   AND (asset_name, locale) NOT IN (
                       SELECT * FROM unnest(?::text[], ?::text[])
                   )
            """.trimIndent()).use { ps ->
                ps.setArray(1, nameArr)
                ps.setArray(2, localeArr)
                ps.executeUpdate()
            }
        })
    }
}
