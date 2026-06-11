package com.zwolsman.ptcgl.mirror.harvester.domain

import java.time.LocalDate

data class SetRecord(
    val id: String,           // lowercase set code, e.g. "sv1"
    val code: String,         // as given by manifest, e.g. "sv1"
    val name: String,         // placeholder = code until a name source is found
    val series: String?,
    val releaseDate: LocalDate?,
    val revision: String,
)

data class SetLocalizationRecord(
    val setId: String,
    val locale: String,
    val name: String,
)

data class CardRecord(
    val id: String,           // e.g. "sv1_1"
    val setId: String,        // e.g. "sv1"
    val number: String,       // formatted collector number, e.g. "001"
    val rarity: String?,
    val regulationMark: String?,
    val archetype: String?,
    val hp: Int?,
    val types: List<String>,
    val evolvesFrom: String?,
    val groupId: String?,
)

data class CardLocalizationRecord(
    val cardId: String,
    val locale: String,
    val name: String,
)
