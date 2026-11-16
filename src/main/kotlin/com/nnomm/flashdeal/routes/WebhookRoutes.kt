package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.ValidationException
import com.nnomm.flashdeal.domain.WebhookPayload
import com.nnomm.flashdeal.service.WebhookService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun Route.webhookRoutes(webhookService: WebhookService) {
    val json = Json { ignoreUnknownKeys = true }

    route("/v1/webhooks/payments") {
        post {
            val signatureHeader = call.request.headers["X-Payment-Signature"]
            val rawBytes = call.receiveRawBytesWithLimit()

            // 1. Signature & timestamp verification (rejects with 401 if invalid)
            webhookService.verifySignature(signatureHeader, rawBytes)

            // 2. Body deserialization
            val payload = try {
                json.decodeFromString<WebhookPayload>(rawBytes.decodeToString())
            } catch (e: SerializationException) {
                throw ValidationException("Malformed webhook payload: ${e.message}")
            }

            // 3. Process payment event
            val response = webhookService.processPaymentWebhook(payload)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
