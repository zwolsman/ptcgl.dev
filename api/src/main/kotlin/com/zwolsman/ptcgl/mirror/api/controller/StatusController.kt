package com.zwolsman.ptcgl.mirror.api.controller

import com.zwolsman.ptcgl.mirror.api.db.StatusQueryRepository
import com.zwolsman.ptcgl.mirror.api.model.ApiStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/")
class StatusController(private val statusRepo: StatusQueryRepository) {

    @GetMapping
    fun status(): ApiStatus = statusRepo.getStatus()
}
