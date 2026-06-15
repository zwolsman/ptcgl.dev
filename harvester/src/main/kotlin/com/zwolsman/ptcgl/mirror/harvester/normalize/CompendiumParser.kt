package com.zwolsman.ptcgl.mirror.harvester.normalize

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zwolsman.ptcgl.mirror.rainier.config.ConfigDoc
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(CompendiumParser::class.java)
private val mapper = jacksonObjectMapper()

data class CompendiumData(
    val totalCards: Int?,
    val rawJson: String,
)

object CompendiumParser {

    fun parse(doc: ConfigDoc): CompendiumData {
        val entry = doc.data.values.firstOrNull()
            ?: return CompendiumData(totalCards = null, rawJson = "{}").also {
                log.warn("Compendium doc '{}' has no data entries", doc.id)
            }

        val rawJson = entry.payloadBase64
        val root = mapper.readTree(rawJson)

        log.info(
            "Compendium doc '{}' top-level keys: {}",
            doc.id,
            root.fieldNames().asSequence().toList(),
        )

        val totalCards = root.findValue("totalCards")?.intValue()
            ?: root.findValue("total_cards")?.intValue()
            ?: root.findValue("cardCount")?.intValue()
            ?: root.findValue("setSize")?.intValue()
            ?: root.findValue("count")?.intValue()

        log.info("Compendium '{}': totalCards={}", doc.id, totalCards)
        return CompendiumData(totalCards = totalCards, rawJson = rawJson)
    }
}
