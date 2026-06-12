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
    val id: String,               // e.g. "sv1_1"
    val setId: String,            // e.g. "sv1"
    val number: String,           // formatted collector number, e.g. "001"
    val rarity: String?,          // Loc Rarity Code, e.g. "C", "RR", "SAR"
    val regulationMark: String?,  // single letter, e.g. "G", "H"
    val archetype: String?,       // archetypeID as string
    val hp: Int?,                 // null for Trainer / Energy cards
    val types: List<String>,      // single-letter codes, e.g. ["G"], ["R","W"]
    val evolvesFrom: String?,
    val groupId: String?,         // groups alternate-art variants of the same card
    val retreat: Int?,            // retreat energy cost; null for Trainer / Energy
    val weaknessType: String?,    // single-letter type code, e.g. "R" (Fire)
    val weaknessAmount: String?,  // multiplier string, e.g. "2" (×2)
    val resistanceType: String?,
    val resistanceAmount: String?,
    val category: Int?,           // 1=Pokémon, 2=Trainer, 3=Energy (System.Byte in DataTable)
)

data class CardLocalizationRecord(
    val cardId: String,
    val locale: String,
    val name: String,
)

// Locale-invariant attack data (cost, damage are energy codes / numbers, stable across locales).
data class CardAttackRecord(
    val cardId: String,
    val slot: Int,         // 1-based position on the card (1..4)
    val cost: String?,     // energy cost string, e.g. "CC", "GGG"
    val damage: String?,   // damage value, e.g. "10", "120+"; null for effect-only attacks
    val attackType: Int?,  // attackType enum value from DataTable
    val attackId: Int?,    // attackID hash (signed Int32)
)

// Locale-specific attack name and rules text.
data class CardAttackLocalizationRecord(
    val cardId: String,
    val slot: Int,         // 1-based, matches CardAttackRecord.slot
    val locale: String,
    val name: String,
    val text: String?,     // rules text; null for attacks with no effect description
)
