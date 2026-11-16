package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.AuthUser
import com.nnomm.flashdeal.domain.PayloadTooLargeException
import com.nnomm.flashdeal.domain.UnauthenticatedException
import com.nnomm.flashdeal.domain.ValidationException
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.utils.io.*

val AuthUserKey = AttributeKey<AuthUser>("AuthUserKey")

suspend fun ApplicationCall.receiveRawBytesWithLimit(maxBytes: Long = 16384): ByteArray {
    val contentLength = request.contentLength()
    if (contentLength != null && contentLength > maxBytes) {
        throw PayloadTooLargeException()
    }

    val channel = request.receiveChannel()
    val byteArray = channel.toByteArray()
    if (byteArray.size > maxBytes) {
        throw PayloadTooLargeException()
    }
    return byteArray
}

fun ApplicationCall.getAuthenticatedUser(): AuthUser {
    return attributes.getOrNull(AuthUserKey)
        ?: throw UnauthenticatedException("Missing or invalid authentication token")
}
