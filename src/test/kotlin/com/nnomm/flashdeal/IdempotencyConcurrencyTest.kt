package com.nnomm.flashdeal

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class IdempotencyConcurrencyTest : BaseIntegrationTest() {

    @Test
    fun `20 concurrent requests with one Idempotency-Key create exactly one claim`() = testApplication {
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
                    title = "Idempotency Race Deal",
                    price_paise = 2500,
                    stock_total = 10,
                    starts_at = startsAt,
                    ends_at = endsAt
                )
            )
        }
        val deal = createRes.body<DealResponse>()

        val customerId = UUID.randomUUID()
        val customerToken = mintCustomerToken(customerId)
        val sharedKey = "shared-idempotency-key-${UUID.randomUUID()}"
        val totalRequests = 20

        val successCount = AtomicInteger(0)
        val inProgressCount = AtomicInteger(0)
        val replayedSuccessCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(10)
        val readyLatch = CountDownLatch(totalRequests)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalRequests)

        for (i in 1..totalRequests) {
            executor.submit {
                readyLatch.countDown()
                startLatch.await()

                try {
                    val res = runBlocking {
                        client.post("/v1/deals/${deal.id}/claims") {
                            header(HttpHeaders.Authorization, "Bearer $customerToken")
                            header("Idempotency-Key", sharedKey)
                            contentType(ContentType.Application.Json)
                            setBody(CreateClaimRequest(quantity = 1))
                        }
                    }

                    when (res.status) {
                        HttpStatusCode.Created -> {
                            val isReplayed = res.headers["Idempotent-Replayed"] == "true"
                            if (isReplayed) {
                                replayedSuccessCount.incrementAndGet()
                            } else {
                                successCount.incrementAndGet()
                            }
                        }
                        HttpStatusCode.Conflict -> {
                            val err = runBlocking { res.body<ErrorResponse>() }
                            if (err.error.code == "REQUEST_IN_PROGRESS") {
                                inProgressCount.incrementAndGet()
                            }
                        }
                        else -> {}
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        readyLatch.await()
        startLatch.countDown()
        doneLatch.await()
        executor.shutdown()

        // Exactly one original request created the claim
        assertEquals(1, successCount.get(), "Exactly one original creation must return 201 without replay header")
        assertTrue(inProgressCount.get() + replayedSuccessCount.get() == 19, "Remaining 19 requests must be IN_PROGRESS or REPLAYED")

        // Database must have exactly 1 claim
        DatabaseFactory.inReadOnly { conn ->
            val stmt = conn.prepareStatement("SELECT COUNT(*) FROM claims WHERE deal_id = ? AND customer_id = ?")
            stmt.setObject(1, UUID.fromString(deal.id))
            stmt.setObject(2, customerId)
            val rs = stmt.executeQuery()
            rs.next()
            assertEquals(1, rs.getInt(1), "Exactly one claim record must exist in database")
        }

        // Subsequent retry must succeed and have Idempotent-Replayed: true
        val retryRes = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", sharedKey)
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }

        assertEquals(HttpStatusCode.Created, retryRes.status)
        assertEquals("true", retryRes.headers["Idempotent-Replayed"])
    }

    @Test
    fun `same idempotency key with different deal or quantity returns 422 IDEMPOTENCY_KEY_REUSED`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        val deal1 = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest("Deal 1", 1000, 10, startsAt, endsAt)
            )
        }.body<DealResponse>()

        val deal2 = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest("Deal 2", 1000, 10, startsAt, endsAt)
            )
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val key = "test-reused-key-${UUID.randomUUID()}"

        // Initial claim on deal 1 with quantity 1
        val firstRes = client.post("/v1/deals/${deal1.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }
        assertEquals(HttpStatusCode.Created, firstRes.status)

        // Retry same key on deal 1 with DIFFERENT quantity (2)
        val diffQtyRes = client.post("/v1/deals/${deal1.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 2))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, diffQtyRes.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", diffQtyRes.body<ErrorResponse>().error.code)

        // Retry same key on deal 2 (different deal)
        val diffDealRes = client.post("/v1/deals/${deal2.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, diffDealRes.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", diffDealRes.body<ErrorResponse>().error.code)
    }

    @Test
    fun `missing or malformed idempotency key returns 400 IDEMPOTENCY_KEY_INVALID`() = testApplication {
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

        // 1. Missing header
        val missingRes = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }
        assertEquals(HttpStatusCode.BadRequest, missingRes.status)
        assertEquals("IDEMPOTENCY_KEY_INVALID", missingRes.body<ErrorResponse>().error.code)

        // 2. Malformed characters (contains space or special chars)
        val malformedRes = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "bad key with spaces!@#")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }
        assertEquals(HttpStatusCode.BadRequest, malformedRes.status)
        assertEquals("IDEMPOTENCY_KEY_INVALID", malformedRes.body<ErrorResponse>().error.code)
    }
}
