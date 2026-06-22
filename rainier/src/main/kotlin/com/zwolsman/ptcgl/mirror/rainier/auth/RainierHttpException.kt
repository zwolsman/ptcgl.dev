package com.zwolsman.ptcgl.mirror.rainier.auth

class RainierHttpException(val statusCode: Int, message: String) : RuntimeException(message)
