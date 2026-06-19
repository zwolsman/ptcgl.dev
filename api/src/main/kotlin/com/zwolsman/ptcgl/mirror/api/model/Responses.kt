package com.zwolsman.ptcgl.mirror.api.model

import java.time.Instant
import java.time.LocalDate

data class ApiStatus(
    val sets: Int,
    val cards: Int,
    val lastSync: Instant?,
)

data class SeriesResponse(
    val id: String,
    val setCount: Int,
)

data class SeriesDetailResponse(
    val id: String,
    val sets: List<SetResponse>,
)

data class SetResponse(
    val id: String,
    val series: String?,
    val code: String,
    val name: String?,
    val releaseDate: LocalDate?,
    /** Numbered cards in the main expansion (null for promo/alt sets). */
    val mainSetCount: Int?,
    /** Full collectible set size including secret rares (null for promo/alt sets). */
    val masterSetCount: Int?,
    val logo: String?,
)

data class CardSummaryResponse(
    val id: String,
    val number: String,
    val position: String?,
    val name: String?,
    val thumb: String?,
)

data class CardResponse(
    val id: String,
    val setId: String,
    val series: String?,
    val number: String,
    val position: String?,
    val name: String?,
    /** POKEMON | TRAINER | ENERGY */
    val category: String?,
    val rarity: String?,
    val regulationMark: String?,
    val hp: Int?,
    val types: List<String>,
    val evolvesFrom: String?,
    /** Card effect text (trainer/energy rule text, Pokémon abilities). Null when absent. */
    val text: String?,
    val retreat: Int?,
    val weakness: Weakness?,
    val resistance: Resistance?,
    /** Same card, different treatment — same set + same number, different ID suffix (e.g. _ph, _sph, _mph). */
    val variants: List<Variant>,
    /** Same archetype, different set or collector number (alt arts, reprints, etc.). */
    val otherPrints: List<OtherPrint>,
    val attacks: List<AttackResponse>,
    val assets: CardAssets,
)

data class Variant(val id: String, val thumb: String?, val type: String)

data class OtherPrint(val id: String, val thumb: String?)

data class Weakness(val type: String, val amount: String)

data class Resistance(val type: String, val amount: String)

data class AttackResponse(
    val slot: Int,
    val name: String?,
    val cost: String?,
    val damage: String?,
    val text: String?,
)

data class CardAssets(
    val hires: String?,
    val thumb: String?,
    val whiteplate: String?,
    val etch: String?,
    val foilType: String?,
)
