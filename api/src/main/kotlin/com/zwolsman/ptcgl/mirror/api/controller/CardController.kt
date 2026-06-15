package com.zwolsman.ptcgl.mirror.api.controller

import com.zwolsman.ptcgl.mirror.api.db.CardQueryRepository
import com.zwolsman.ptcgl.mirror.api.model.CardResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v1/cards")
class CardController(private val cardRepo: CardQueryRepository) {

    @GetMapping("/{id}")
    fun getCard(
        @PathVariable id: String,
        @RequestParam(defaultValue = "en") locale: String,
    ): CardResponse =
        cardRepo.findById(id, locale) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
