package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.domain.ValidationException
import com.nnomm.flashdeal.service.DealService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun Route.dealRoutes(dealService: DealService) {
    val json = Json { ignoreUnknownKeys = true }

    route("/v1/deals") {
        post {
            val caller = call.getAuthenticatedUser()
            val rawBytes = call.receiveRawBytesWithLimit()
            val request = try {
                json.decodeFromString<CreateDealRequest>(rawBytes.decodeToString())
            } catch (e: SerializationException) {
                throw ValidationException("Invalid request body: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw ValidationException("Invalid field format: ${e.message}")
            }

            val deal = dealService.createDeal(caller, request)
            call.respond(HttpStatusCode.Created, deal)
        }

        get("/{dealId}") {
            // Any authenticated user can get a deal
            call.getAuthenticatedUser()
            val dealId = call.parameters["dealId"] ?: throw ValidationException("Missing dealId")
            val deal = dealService.getDeal(dealId)
            call.respond(HttpStatusCode.OK, deal)
        }
    }
}
