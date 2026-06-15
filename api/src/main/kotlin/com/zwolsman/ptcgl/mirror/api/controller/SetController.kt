package com.zwolsman.ptcgl.mirror.api.controller

import com.zwolsman.ptcgl.mirror.api.db.CardQueryRepository
import com.zwolsman.ptcgl.mirror.api.db.SetQueryRepository
import com.zwolsman.ptcgl.mirror.api.model.CardResponse
import com.zwolsman.ptcgl.mirror.api.model.SetResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v1/sets")
class SetController(
    private val setRepo: SetQueryRepository,
    private val cardRepo: CardQueryRepository,
) {

    @GetMapping
    fun listSets(): List<SetResponse> = setRepo.findAll()

    @GetMapping("/{id}")
    fun getSet(@PathVariable id: String): SetResponse =
        setRepo.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping("/{id}/cards")
    fun getCards(
        @PathVariable id: String,
        @RequestParam(defaultValue = "en") locale: String,
    ): List<CardResponse> = cardRepo.findBySetId(id, locale)
}
