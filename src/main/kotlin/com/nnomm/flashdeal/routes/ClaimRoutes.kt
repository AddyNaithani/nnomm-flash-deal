package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.CreateClaimRequest
import com.nnomm.flashdeal.domain.IdempotencyKeyInvalidException
import com.nnomm.flashdeal.domain.ValidationException
import com.nnomm.flashdeal.service.ClaimService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun Route.claimRoutes(claimService: ClaimService) {
    val json = Json { ignoreUnknownKeys = true }

    route("/v1/deals/{dealId}/claims") {
        post {
            val caller = call.getAuthenticatedUser()
            val dealId = call.parameters["dealId"] ?: throw ValidationException("Missing dealId")
            val idempotencyKey = call.request.headers["Idempotency-Key"]
                ?: throw IdempotencyKeyInvalidException("Missing Idempotency-Key header")

            val rawBytes = call.receiveRawBytesWithLimit()
            val request = try {
                json.decodeFromString<CreateClaimRequest>(rawBytes.decodeToString())
            } catch (e: SerializationException) {
                throw ValidationException("Invalid request body: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw ValidationException("Invalid field format: ${e.message}")
            }

            val result = claimService.claim(
                caller = caller,
                dealIdStr = dealId,
                idempotencyKey = idempotencyKey,
                quantity = request.quantity
            )

            if (result.isReplayed) {
                call.response.headers.append("Idempotent-Replayed", "true")
            }

            call.respondText(
                text = result.body,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.fromValue(result.statusCode)
            )
        }
    }

    route("/v1/claims/{claimId}") {
        get {
            val caller = call.getAuthenticatedUser()
            val claimId = call.parameters["claimId"] ?: throw ValidationException("Missing claimId")
            val claim = claimService.getClaim(caller, claimId)
            call.respond(HttpStatusCode.OK, claim)
        }
    }
}
