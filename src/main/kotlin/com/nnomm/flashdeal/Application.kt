package com.nnomm.flashdeal

import com.nnomm.flashdeal.config.AppConfig
import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.db.MigrationManager
import com.nnomm.flashdeal.domain.*
import com.nnomm.flashdeal.repository.ClaimRepository
import com.nnomm.flashdeal.repository.DealRepository
import com.nnomm.flashdeal.repository.IdempotencyRepository
import com.nnomm.flashdeal.repository.WebhookRepository
import com.nnomm.flashdeal.routes.*
import com.nnomm.flashdeal.security.JwtService
import com.nnomm.flashdeal.security.WebhookSignatureVerifier
import com.nnomm.flashdeal.service.ClaimService
import com.nnomm.flashdeal.service.DealService
import com.nnomm.flashdeal.service.ExpiryReaperService
import com.nnomm.flashdeal.service.WebhookService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val config = AppConfig()
    val dataSource = DatabaseFactory.init(config)
    MigrationManager.migrate(dataSource)

    val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val reaper = ExpiryReaperService()
    reaper.start(serverScope)

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        reaper.stop()
        server.stop(1000, 5000)
        DatabaseFactory.close()
    })

    server.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig()) {
    val logger = LoggerFactory.getLogger("Application")
    val jwtService = JwtService(config.jwtSecret)
    val webhookVerifier = WebhookSignatureVerifier(config.webhookSecret)

    val dealRepository = DealRepository()
    val claimRepository = ClaimRepository()
    val idempotencyRepository = IdempotencyRepository()
    val webhookRepository = WebhookRepository()

    val dealService = DealService(dealRepository)
    val claimService = ClaimService(claimRepository, idempotencyRepository, config.claimTtlSeconds)
    val webhookService = WebhookService(webhookRepository, webhookVerifier)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = false
            encodeDefaults = true
        })
    }

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                status = cause.statusCode,
                message = ErrorResponse(ErrorDetails(code = cause.errorCode, message = cause.message))
            )
        }

        exception<PayloadTooLargeException> { call, cause ->
            call.respond(
                status = HttpStatusCode.PayloadTooLarge,
                message = ErrorResponse(ErrorDetails(code = cause.errorCode, message = cause.message))
            )
        }

        exception<SerializationException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ErrorResponse(ErrorDetails(code = "VALIDATION_FAILED", message = cause.message ?: "Invalid JSON body"))
            )
        }

        exception<Throwable> { call, cause ->
            logger.error("Unhandled error: ${cause.message}", cause)
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ErrorResponse(ErrorDetails(code = "INTERNAL", message = "An internal server error occurred"))
            )
        }
    }

    // Authentication Interceptor
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        // Skip auth for webhook, health, and demo dashboard endpoints
        if (path.startsWith("/v1/webhooks") || path == "/health" || path == "/" || path.startsWith("/demo")) {
            return@intercept
        }

        val authHeader = call.request.headers["Authorization"]
        if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
            throw UnauthenticatedException("Missing or invalid Bearer token")
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        val authUser = jwtService.verifyToken(token)
        call.attributes.put(AuthUserKey, authUser)
    }

    routing {
        dealRoutes(dealService)
        claimRoutes(claimService)
        webhookRoutes(webhookService)
        dashboardRoutes(jwtService, webhookVerifier)
        healthRoutes()
    }
}
