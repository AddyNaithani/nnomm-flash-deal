package com.nnomm.flashdeal

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import com.nnomm.flashdeal.repository.ClaimRepository
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class PaymentExpiryRaceTest : BaseIntegrationTest() {

    @RepeatedTest(5)
    fun `payment and expiry racing on the same claim leave the stock invariant intact`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = mintAdminToken()
        val startsAt = Clock.System.now()
        val endsAt = startsAt.plus(2.hours)

        // 1. Create deal with 1 unit
        val createRes = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest("Race Biryani", 9900, 1, startsAt, endsAt)
            )
        }
        val deal = createRes.body<DealResponse>()

        // 2. Customer claims the only unit
        val customerToken = mintCustomerToken()
        val claimRes = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "race-key-${UUID.randomUUID()}")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }
        val claim = claimRes.body<ClaimResponse>()
        assertEquals("RESERVED", claim.status)

        // Verify stock is now 0
        DatabaseFactory.inReadOnly { conn ->
            val rs = conn.prepareStatement("SELECT stock_available FROM deals WHERE id = '${deal.id}'").executeQuery()
            rs.next()
            assertEquals(0, rs.getInt(1))
        }

        // 3. Artificially set expires_at to 1 second ago so that both payment and expiry are immediately eligible
        DatabaseFactory.inTransaction { conn ->
            val updateStmt = conn.prepareStatement("UPDATE claims SET expires_at = NOW() - INTERVAL '1 second' WHERE id = ?")
            updateStmt.setObject(1, UUID.fromString(claim.id))
            updateStmt.executeUpdate()
        }

        // 4. Prepare payment webhook payload and signature
        val eventPayload = WebhookPayload(
            event_id = "evt_race_${UUID.randomUUID()}",
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 9900,
            occurred_at = Clock.System.now()
        )
        val rawBody = json.encodeToString(eventPayload).toByteArray(Charsets.UTF_8)
        val signature = webhookVerifier.sign(rawBody)

        val executor = Executors.newFixedThreadPool(2)
        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        val claimRepository = ClaimRepository()

        // Thread 1: Expiry Reaper attempt
        executor.submit {
            readyLatch.countDown()
            startLatch.await()
            try {
                runBlocking {
                    DatabaseFactory.inTransaction { conn ->
                        claimRepository.expireReservedClaims(conn)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Reaper execution note: ${e.message}")
            } finally {
                doneLatch.countDown()
            }
        }

        // Thread 2: Payment Webhook arriving
        executor.submit {
            readyLatch.countDown()
            startLatch.await()
            try {
                runBlocking {
                    client.post("/v1/webhooks/payments") {
                        header("X-Payment-Signature", signature)
                        contentType(ContentType.Application.Json)
                        setBody(rawBody)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Webhook execution note: ${e.message}")
            } finally {
                doneLatch.countDown()
            }
        }

        readyLatch.await()
        startLatch.countDown() // Both execute simultaneously!
        doneLatch.await()
        executor.shutdown()

        // 5. Invariant Verification:
        // Exactly one outcome happened:
        // A) Claim ends PAID, units stay sold (stock_available = 0)
        // B) Claim ends REFUND_PENDING, units returned (stock_available = 1)
        DatabaseFactory.inReadOnly { conn ->
            val claimStmt = conn.prepareStatement("SELECT status FROM claims WHERE id = ?")
            claimStmt.setObject(1, UUID.fromString(claim.id))
            val rsClaim = claimStmt.executeQuery()
            rsClaim.next()
            val finalStatus = rsClaim.getString("status")

            val dealStmt = conn.prepareStatement("SELECT stock_available, stock_total FROM deals WHERE id = ?")
            dealStmt.setObject(1, UUID.fromString(deal.id))
            val rsDeal = dealStmt.executeQuery()
            rsDeal.next()
            val finalAvailable = rsDeal.getInt("stock_available")
            val stockTotal = rsDeal.getInt("stock_total")

            assertTrue(
                finalStatus == "PAID" || finalStatus == "REFUND_PENDING",
                "Final status must be PAID or REFUND_PENDING, but got: $finalStatus"
            )

            if (finalStatus == "PAID") {
                assertEquals(0, finalAvailable, "When PAID, units stay sold: available must be 0")
            } else {
                assertEquals(1, finalAvailable, "When REFUND_PENDING, units must be back in stock: available must be 1")
            }

            // Total invariant: stock_available + active_units == stock_total
            val activeQty = if (finalStatus in listOf("RESERVED", "PAID", "NEEDS_REVIEW")) 1 else 0
            assertEquals(stockTotal, finalAvailable + activeQty, "Invariant violated! stock_available + active != stock_total")
        }
    }
}
