package com.nnomm.flashdeal

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class AuthorizationBoundaryTest : BaseIntegrationTest() {

    @Test
    fun `customers cannot read other customers claims - returns 404 NOT_FOUND`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        val deal = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(CreateDealRequest("Deal", 1000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerA = UUID.randomUUID()
        val tokenA = mintCustomerToken(customerA)
        val customerB = UUID.randomUUID()
        val tokenB = mintCustomerToken(customerB)

        // Customer A creates claim
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            header("Idempotency-Key", "customer-a-claim")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }.body<ClaimResponse>()

        // Customer B tries to read Customer A's claim
        val unauthorizedReadRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }

        // Must return 404 NOT_FOUND, NOT 403!
        assertEquals(HttpStatusCode.NotFound, unauthorizedReadRes.status)
        val error = unauthorizedReadRes.body<ErrorResponse>()
        assertEquals("NOT_FOUND", error.error.code)

        // Customer A can read their own claim
        val authorizedReadRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, authorizedReadRes.status)
        assertEquals(claim.id, authorizedReadRes.body<ClaimResponse>().id)
    }

    @Test
    fun `admins cannot read claims on other restaurants deals - returns 404 NOT_FOUND`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurant1 = UUID.randomUUID()
        val adminTokenRest1 = mintAdminToken(restaurantId = restaurant1)

        val restaurant2 = UUID.randomUUID()
        val adminTokenRest2 = mintAdminToken(restaurantId = restaurant2)

        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        // Deal created for Restaurant 1
        val deal = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenRest1")
            contentType(ContentType.Application.Json)
            setBody(CreateDealRequest("Rest 1 Deal", 1000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "customer-claim-rest1")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }.body<ClaimResponse>()

        // Admin of Restaurant 2 tries to read the claim on Restaurant 1's deal
        val unauthorizedAdminRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenRest2")
        }

        // Must return 404 NOT_FOUND, NOT 403!
        assertEquals(HttpStatusCode.NotFound, unauthorizedAdminRes.status)
        assertEquals("NOT_FOUND", unauthorizedAdminRes.body<ErrorResponse>().error.code)

        // Admin of Restaurant 1 CAN read the claim on their own deal
        val authorizedAdminRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenRest1")
        }
        assertEquals(HttpStatusCode.OK, authorizedAdminRes.status)
        assertEquals(claim.id, authorizedAdminRes.body<ClaimResponse>().id)
    }

    @Test
    fun `expired claim is never shown as RESERVED via GET claim endpoint`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        val deal = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(CreateDealRequest("Deal", 1000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "claim-expire-display")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }.body<ClaimResponse>()

        // Artificially expire the claim in DB while leaving status = RESERVED
        DatabaseFactory.inTransaction { conn ->
            val stmt = conn.prepareStatement("UPDATE claims SET expires_at = NOW() - INTERVAL '5 seconds' WHERE id = ?")
            stmt.setObject(1, UUID.fromString(claim.id))
            stmt.executeUpdate()
        }

        // Fetching the claim must dynamically return EXPIRED even before the reaper worker updates it!
        val fetchedClaim = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }.body<ClaimResponse>()

        assertEquals("EXPIRED", fetchedClaim.status, "From expires_at on, the API never shows it as RESERVED")
    }

    @Test
    fun `missing or tampered JWT token returns 401 UNAUTHENTICATED`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Missing header
        val res1 = client.get("/v1/deals/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Unauthorized, res1.status)
        assertEquals("UNAUTHENTICATED", res1.body<ErrorResponse>().error.code)

        // 2. Tampered token
        val validToken = mintCustomerToken()
        val tamperedToken = validToken.substring(0, validToken.length - 5) + "abcde"
        val res2 = client.get("/v1/deals/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $tamperedToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, res2.status)
        assertEquals("UNAUTHENTICATED", res2.body<ErrorResponse>().error.code)
    }
}
