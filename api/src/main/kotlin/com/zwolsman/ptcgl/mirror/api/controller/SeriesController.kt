package com.zwolsman.ptcgl.mirror.api.controller

import com.zwolsman.ptcgl.mirror.api.db.SetQueryRepository
import com.zwolsman.ptcgl.mirror.api.model.SeriesDetailResponse
import com.zwolsman.ptcgl.mirror.api.model.SeriesResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v1/series")
class SeriesController(private val setRepo: SetQueryRepository) {

    @GetMapping
    fun listSeries(): List<SeriesResponse> = setRepo.findAllSeries()

    @GetMapping("/{series}")
    fun getSeries(
        @PathVariable series: String,
        @RequestParam(defaultValue = "en") locale: String,
    ): SeriesDetailResponse {
        val sets = setRepo.findBySeries(series, locale)
        if (sets.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return SeriesDetailResponse(id = series, sets = sets)
    }
}
