package com.nnomm.flashdeal.domain

import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorDetails(
    val code: String,
    val message: String
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetails
)

open class ApiException(
    val statusCode: HttpStatusCode,
    val errorCode: String,
    override val message: String
) : RuntimeException(message)

class ValidationException(message: String) : ApiException(
    HttpStatusCode.BadRequest,
    "VALIDATION_FAILED",
    message
)

class IdempotencyKeyInvalidException(message: String = "Idempotency-Key is missing or malformed") : ApiException(
    HttpStatusCode.BadRequest,
    "IDEMPOTENCY_KEY_INVALID",
    message
)

class UnauthenticatedException(message: String = "Bad or expired token, or a bad webhook signature") : ApiException(
    HttpStatusCode.Unauthorized,
    "UNAUTHENTICATED",
    message
)

class ForbiddenException(message: String = "The caller's role can't use this endpoint") : ApiException(
    HttpStatusCode.Forbidden,
    "FORBIDDEN",
    message
)

class NotFoundException(message: String = "It doesn't exist, or the caller may not see it") : ApiException(
    HttpStatusCode.NotFound,
    "NOT_FOUND",
    message
)

class SoldOutException(message: String = "Not enough units left for this quantity") : ApiException(
    HttpStatusCode.Conflict,
    "SOLD_OUT",
    message
)

class AlreadyClaimedException(message: String = "The customer already holds an active claim on this deal") : ApiException(
    HttpStatusCode.Conflict,
    "ALREADY_CLAIMED",
    message
)

class DealNotLiveException(message: String = "The deal hasn't started yet, or has ended") : ApiException(
    HttpStatusCode.Conflict,
    "DEAL_NOT_LIVE",
    message
)

class RequestInProgressException(message: String = "A request with the same key is still running") : ApiException(
    HttpStatusCode.Conflict,
    "REQUEST_IN_PROGRESS",
    message
)

class PayloadTooLargeException(message: String = "The body is over 16 KB") : ApiException(
    HttpStatusCode.PayloadTooLarge,
    "PAYLOAD_TOO_LARGE",
    message
)

class IdempotencyKeyReusedException(message: String = "Same key, different request") : ApiException(
    HttpStatusCode.UnprocessableEntity,
    "IDEMPOTENCY_KEY_REUSED",
    message
)
