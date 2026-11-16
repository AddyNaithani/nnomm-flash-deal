package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.domain.DealResponse
import com.nnomm.flashdeal.domain.ErrorResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class DealEndpointTest : BaseIntegrationTest() {

    @Test
    fun `restaurant admin can successfully create deal`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurantId = UUID.randomUUID()
        val token = mintAdminToken(restaurantId = restaurantId)

        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        val response = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Chicken Biryani Box",
                    price_paise = 9900,
                    stock_total = 50,
                    starts_at = startsAt,
                    ends_at = endsAt
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val createdDeal = response.body<DealResponse>()
        assertEquals(restaurantId.toString(), createdDeal.restaurant_id)
        assertEquals("Chicken Biryani Box", createdDeal.title)
        assertEquals(9900, createdDeal.price_paise)
        assertEquals(50, createdDeal.stock_total)
        assertEquals(50, createdDeal.stock_available)
        assertNotNull(createdDeal.id)
    }

    @Test
    fun `customer cannot create a deal - returns 403 FORBIDDEN`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val token = mintCustomerToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        val response = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Customer Attempt",
                    price_paise = 5000,
                    stock_total = 10,
                    starts_at = startsAt,
                    ends_at = endsAt
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ErrorResponse>()
        assertEquals("FORBIDDEN", error.error.code)
    }

    @Test
    fun `deal validation fails on invalid limits - returns 400 VALIDATION_FAILED`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val token = mintAdminToken()
        val now = Clock.System.now()

        // 1. starts_at after ends_at
        val invalidTimeResponse = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Invalid Time",
                    price_paise = 5000,
                    stock_total = 10,
                    starts_at = now.plus(2.hours),
                    ends_at = now
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidTimeResponse.status)
        assertEquals("VALIDATION_FAILED", invalidTimeResponse.body<ErrorResponse>().error.code)

        // 2. stock_total > 10000
        val invalidStockResponse = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Huge Stock",
                    price_paise = 5000,
                    stock_total = 10001,
                    starts_at = now,
                    ends_at = now.plus(2.hours)
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidStockResponse.status)
        assertEquals("VALIDATION_FAILED", invalidStockResponse.body<ErrorResponse>().error.code)

        // 3. title > 100 characters
        val longTitleResponse = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "A".repeat(101),
                    price_paise = 5000,
                    stock_total = 10,
                    starts_at = now,
                    ends_at = now.plus(2.hours)
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, longTitleResponse.status)
        assertEquals("VALIDATION_FAILED", longTitleResponse.body<ErrorResponse>().error.code)
    }

    @Test
    fun `payload exceeding 16 KB returns 413 PAYLOAD_TOO_LARGE`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val token = mintAdminToken()
        val hugeBody = "{\"extra\":\"" + "X".repeat(17000) + "\"}"

        val response = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(hugeBody)
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val error = response.body<ErrorResponse>()
        assertEquals("PAYLOAD_TOO_LARGE", error.error.code)
    }

    @Test
    fun `getting deal by ID returns 200 for any authenticated user`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        val createRes = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Biryani",
                    price_paise = 8500,
                    stock_total = 20,
                    starts_at = startsAt,
                    ends_at = endsAt
                )
            )
        }
        val createdDeal = createRes.body<DealResponse>()

        // Customer fetching the deal
        val customerToken = mintCustomerToken()
        val getRes = client.get("/v1/deals/${createdDeal.id}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }

        assertEquals(HttpStatusCode.OK, getRes.status)
        val fetchedDeal = getRes.body<DealResponse>()
        assertEquals(createdDeal.id, fetchedDeal.id)
        assertEquals(20, fetchedDeal.stock_available)
    }
}
