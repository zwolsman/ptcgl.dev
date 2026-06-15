package com.zwolsman.ptcgl.mirror.api.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException

@RestControllerAdvice
class JsonErrorController {

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNotFound(ex: NoHandlerFoundException): ResponseEntity<Map<String, Any?>> =
        error(HttpStatus.NOT_FOUND)

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, Any?>> {
        val status = HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        return error(status, ex.reason)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception): ResponseEntity<Map<String, Any?>> =
        error(HttpStatus.INTERNAL_SERVER_ERROR)

    private fun error(status: HttpStatus, message: String? = null): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(status).body(
            buildMap {
                put("status", status.value())
                put("error", status.reasonPhrase)
                if (message != null) put("message", message)
            }
        )
}
