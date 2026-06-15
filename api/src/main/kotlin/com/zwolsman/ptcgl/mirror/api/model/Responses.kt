package com.zwolsman.ptcgl.mirror.api.model

import java.time.LocalDate

data class SetResponse(
    val id: String,
    val code: String,
    val series: String?,
    val releaseDate: LocalDate?,
    /** locale → localized name */
    val localizations: Map<String, String>,
)

data class CardResponse(
    val id: String,
    val setId: String,
    val number: String,
    /** POKEMON | TRAINER | ENERGY */
    val category: String?,
    val rarity: String?,
    val regulationMark: String?,
    val archetype: String?,
    val hp: Int?,
    val types: List<String>,
    val evolvesFrom: String?,
    val retreat: Int?,
    val weakness: Weakness?,
    val resistance: Resistance?,
    /** locale → localized name */
    val localizations: Map<String, String>,
    val attacks: List<AttackResponse>,
    val assets: CardAssets,
)

data class Weakness(val type: String, val amount: String)

data class Resistance(val type: String, val amount: String)

data class AttackResponse(
    val slot: Int,
    val cost: String?,
    val damage: String?,
    /** locale → name + rules text */
    val localizations: Map<String, AttackLocalization>,
)

data class AttackLocalization(val name: String, val text: String?)

data class CardAssets(
    val hires: String?,
    val thumb: String?,
)
