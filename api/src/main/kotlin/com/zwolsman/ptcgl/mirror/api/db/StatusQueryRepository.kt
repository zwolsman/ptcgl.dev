package com.zwolsman.ptcgl.mirror.api.db

import com.zwolsman.ptcgl.mirror.api.model.ApiStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class StatusQueryRepository(private val jdbc: JdbcTemplate) {

    fun getStatus(): ApiStatus {
        val sets  = jdbc.queryForObject("""SELECT COUNT(*) FROM "set"""", Int::class.java) ?: 0
        val cards = jdbc.queryForObject("SELECT COUNT(*) FROM card", Int::class.java) ?: 0
        val lastSync = jdbc.queryForObject(
            "SELECT MAX(fetched_at) FROM config_revision",
            Instant::class.java,
        )
        return ApiStatus(sets = sets, cards = cards, lastSync = lastSync)
    }
}
