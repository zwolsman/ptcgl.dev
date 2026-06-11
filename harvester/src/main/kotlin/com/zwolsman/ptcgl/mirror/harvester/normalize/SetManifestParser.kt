package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zwolsman.ptcgl.mirror.harvester.domain.SetRecord
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDoc
import java.time.LocalDate

private val mapper = jacksonObjectMapper()

/**
 * Parses set-manifest_0.0 config doc into [SetRecord] list.
 *
 * set-manifest_0.0 structure:
 *   key "manifest"   → JSON array of set codes: ["ec", "sv1", "swsh12", ...]
 *   key "setDetails" → JSON object: { "sv1": { SeriesId, OAReleaseDate, ... }, ... }
 *
 * OAReleaseDate is an OLE Automation serial date (days since 1899-12-30).
 */
object SetManifestParser {

    fun parse(doc: ConfigDoc): List<SetRecord> {
        val manifestEntry  = doc["manifest"]  ?: error("set-manifest missing 'manifest' key")
        val setDetailsEntry = doc["setDetails"] ?: error("set-manifest missing 'setDetails' key")

        val codes      = mapper.readValue<List<String>>(manifestEntry.payloadBase64)
        val detailsMap = mapper.readValue<Map<String, Map<String, Any?>>>(setDetailsEntry.payloadBase64)

        return codes.map { code ->
            val details = detailsMap[code] ?: emptyMap()
            val oleDate = when (val d = details["OAReleaseDate"]) {
                is Number -> d.toDouble()
                else -> null
            }
            SetRecord(
                id          = code,
                code        = code,
                name        = code,   // placeholder; no name source in set-manifest yet
                series      = details["SeriesId"]?.toString(),
                releaseDate = oleDate?.let { oleToLocalDate(it) },
                revision    = doc.revision,
            )
        }
    }

    private fun oleToLocalDate(oleSerial: Double): LocalDate =
        LocalDate.of(1899, 12, 30).plusDays(oleSerial.toLong())
}
