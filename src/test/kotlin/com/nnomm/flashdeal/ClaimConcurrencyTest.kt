package com.nnomm.flashdeal

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ClaimConcurrencyTest : BaseIntegrationTest() {

    @Test
    fun `200 concurrent claims never oversell a 50-unit deal`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        // 1. Create a deal with 50 units
        val createRes = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Flash Deal 50 Units",
                    price_paise = 4900,
                    stock_total = 50,
                    starts_at = startsAt,
                    ends_at = endsAt
                )
            )
        }
        val deal = createRes.body<DealResponse>()
        assertEquals(50, deal.stock_total)

        // 2. Prepare 200 distinct customers with distinct idempotency keys
        val totalRequests = 200
        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)
        val otherStatusMap = ConcurrentHashMap<HttpStatusCode, AtomicInteger>()

        val executor = Executors.newFixedThreadPool(32)
        val readyLatch = CountDownLatch(totalRequests)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalRequests)

        for (i in 1..totalRequests) {
            val customerId = UUID.randomUUID()
            val token = mintCustomerToken(customerId)
            val key = "key-client-$i-${UUID.randomUUID()}"

            executor.submit {
                readyLatch.countDown()
                startLatch.await()

                try {
                    val res = runBlocking {
                        client.post("/v1/deals/${deal.id}/claims") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header("Idempotency-Key", key)
                            contentType(ContentType.Application.Json)
                            setBody(CreateClaimRequest(quantity = 1))
                        }
                    }

                    when (res.status) {
                        HttpStatusCode.Created -> successCount.incrementAndGet()
                        HttpStatusCode.Conflict -> {
                            val err = runBlocking { res.body<ErrorResponse>() }
                            if (err.error.code == "SOLD_OUT") {
                                soldOutCount.incrementAndGet()
                            } else {
                                otherStatusMap.computeIfAbsent(res.status) { AtomicInteger(0) }.incrementAndGet()
                            }
                        }
                        else -> {
                            otherStatusMap.computeIfAbsent(res.status) { AtomicInteger(0) }.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Request exception: ${e.message}")
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        readyLatch.await()
        startLatch.countDown() // Release all 200 requests at the exact same instant!
        doneLatch.await()
        executor.shutdown()

        // 3. Verify exactly 50 succeeded and 150 were SOLD_OUT
        assertEquals(50, successCount.get(), "Exactly 50 claims must succeed for a 50-unit deal")
        assertEquals(150, soldOutCount.get(), "Exactly 150 claims must fail with SOLD_OUT")
        assertTrue(otherStatusMap.isEmpty(), "No unexpected status codes occurred: $otherStatusMap")

        // 4. Verify Database Invariant
        val updatedDeal = client.get("/v1/deals/${deal.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.body<DealResponse>()

        assertEquals(0, updatedDeal.stock_available, "Available stock must be exactly 0")

        // Direct DB verification
        DatabaseFactory.inReadOnly { conn ->
            val claimCountStmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM claims WHERE deal_id = ? AND status = 'RESERVED'")
            claimCountStmt.setObject(1, UUID.fromString(deal.id))
            val rs = claimCountStmt.executeQuery()
            rs.next()
            val count = rs.getInt(1)
            val totalQty = rs.getInt(2)
            assertEquals(50, count, "Must have exactly 50 claims in RESERVED status")
            assertEquals(50, totalQty, "Total quantity held must equal stock_total (50)")
        }
    }

    @Test
    fun `concurrent claims by one customer with different keys create exactly one claim`() = testApplication {
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
                    title = "Single Customer Deal",
                    price_paise = 2000,
                    stock_total = 100,
                    starts_at = startsAt,
                    ends_at = endsAt
                )
            )
        }
        val deal = createRes.body<DealResponse>()

        // 1 customer fires 20 concurrent requests with different idempotency keys
        val customerId = UUID.randomUUID()
        val token = mintCustomerToken(customerId)
        val totalAttempts = 20

        val successCount = AtomicInteger(0)
        val alreadyClaimedCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(10)
        val readyLatch = CountDownLatch(totalAttempts)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(totalAttempts)

        for (i in 1..totalAttempts) {
            val key = "diff-key-$i-${UUID.randomUUID()}"
            executor.submit {
                readyLatch.countDown()
                startLatch.await()

                try {
                    val res = runBlocking {
                        client.post("/v1/deals/${deal.id}/claims") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header("Idempotency-Key", key)
                            contentType(ContentType.Application.Json)
                            setBody(CreateClaimRequest(quantity = 1))
                        }
                    }

                    if (res.status == HttpStatusCode.Created) {
                        successCount.incrementAndGet()
                    } else if (res.status == HttpStatusCode.Conflict) {
                        val err = runBlocking { res.body<ErrorResponse>() }
                        if (err.error.code == "ALREADY_CLAIMED") {
                            alreadyClaimedCount.incrementAndGet()
                        }
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

        assertEquals(1, successCount.get(), "Exactly one claim should succeed for the same customer")
        assertEquals(19, alreadyClaimedCount.get(), "19 requests should fail with ALREADY_CLAIMED")
    }

    @Test
    fun `claims outside live window return 409 DEAL_NOT_LIVE`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val now = Clock.System.now()

        // Deal starting in 1 hour
        val createRes = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Future Deal",
                    price_paise = 3000,
                    stock_total = 10,
                    starts_at = now.plus(1.hours),
                    ends_at = now.plus(3.hours)
                )
            )
        }
        val deal = createRes.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val res = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "future-key-1")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }

        assertEquals(HttpStatusCode.Conflict, res.status)
        val err = res.body<ErrorResponse>()
        assertEquals("DEAL_NOT_LIVE", err.error.code)
    }
}
