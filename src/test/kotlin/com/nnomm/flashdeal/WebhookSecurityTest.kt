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
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class WebhookSecurityTest : BaseIntegrationTest() {

    @Test
    fun `valid signature and timestamp processes webhook successfully`() = testApplication {
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
            setBody(CreateDealRequest("Deal", 10000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "claim-for-webhook-1")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }.body<ClaimResponse>()

        val eventPayload = WebhookPayload(
            event_id = "evt_valid_1",
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 10000,
            occurred_at = Clock.System.now()
        )
        val rawBody = json.encodeToString(eventPayload).toByteArray(Charsets.UTF_8)
        val signature = webhookVerifier.sign(rawBody)

        val response = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", signature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify claim is PAID
        val updatedClaim = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }.body<ClaimResponse>()
        assertEquals("PAID", updatedClaim.status)
    }

    @Test
    fun `missing or malformed or bad signature returns 401 UNAUTHENTICATED`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val eventPayload = WebhookPayload(
            event_id = "evt_sig_fail_1",
            type = "payment.succeeded",
            claim_id = UUID.randomUUID().toString(),
            amount_paise = 5000,
            occurred_at = Clock.System.now()
        )
        val rawBody = json.encodeToString(eventPayload).toByteArray(Charsets.UTF_8)

        // 1. Missing header
        val missingRes = client.post("/v1/webhooks/payments") {
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, missingRes.status)
        assertEquals("UNAUTHENTICATED", missingRes.body<ErrorResponse>().error.code)

        // 2. Malformed header
        val malformedRes = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", "random_garbage_string")
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, malformedRes.status)
        assertEquals("UNAUTHENTICATED", malformedRes.body<ErrorResponse>().error.code)

        // 3. Tampered signature
        val validSig = webhookVerifier.sign(rawBody)
        val tamperedSig = validSig.replace("v1=", "v1=deadbeef")
        val tamperedRes = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", tamperedSig)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, tamperedRes.status)
        assertEquals("UNAUTHENTICATED", tamperedRes.body<ErrorResponse>().error.code)
    }

    @Test
    fun `stale timestamp over 300 seconds returns 401 UNAUTHENTICATED`() = testApplication {
        application { module(defaultTestConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val eventPayload = WebhookPayload(
            event_id = "evt_stale_1",
            type = "payment.succeeded",
            claim_id = UUID.randomUUID().toString(),
            amount_paise = 5000,
            occurred_at = Clock.System.now()
        )
        val rawBody = json.encodeToString(eventPayload).toByteArray(Charsets.UTF_8)

        // 301 seconds in the past
        val staleUnix = (System.currentTimeMillis() / 1000) - 301
        val staleSignature = webhookVerifier.sign(rawBody, unixSeconds = staleUnix)

        val res = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", staleSignature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("UNAUTHENTICATED", res.body<ErrorResponse>().error.code)
    }

    @Test
    fun `duplicate webhook event returns 200 with no change`() = testApplication {
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
            setBody(CreateDealRequest("Deal", 10000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "claim-for-dup-webhook")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }.body<ClaimResponse>()

        val eventPayload = WebhookPayload(
            event_id = "evt_dup_test_123",
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 10000,
            occurred_at = Clock.System.now()
        )
        val rawBody = json.encodeToString(eventPayload).toByteArray(Charsets.UTF_8)
        val signature = webhookVerifier.sign(rawBody)

        // First delivery
        val res1 = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", signature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.OK, res1.status)

        // Second duplicate delivery
        val res2 = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", signature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.OK, res2.status)

        // Verify database only has 1 record for this event_id
        DatabaseFactory.inReadOnly { conn ->
            val rs = conn.prepareStatement("SELECT COUNT(*) FROM processed_webhook_events WHERE event_id = 'evt_dup_test_123'").executeQuery()
            rs.next()
            assertEquals(1, rs.getInt(1))
        }
    }

    @Test
    fun `amount mismatch transitions claim to NEEDS_REVIEW`() = testApplication {
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
            setBody(CreateDealRequest("Deal", 5000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "claim-wrong-amount")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 2)) // Expecting 10000 paise
        }.body<ClaimResponse>()

        // Webhook pays 7500 instead of 10000
        val eventPayload = WebhookPayload(
            event_id = "evt_wrong_amt_${UUID.randomUUID()}",
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 7500,
            occurred_at = Clock.System.now()
        )
        val rawBody = json.encodeToString(eventPayload).toByteArray(Charsets.UTF_8)
        val signature = webhookVerifier.sign(rawBody)

        val res = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", signature)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val updatedClaim = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }.body<ClaimResponse>()
        assertEquals("NEEDS_REVIEW", updatedClaim.status)
    }

    @Test
    fun `payment failed event leaves claim in RESERVED status so customer can pay again`() = testApplication {
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
            setBody(CreateDealRequest("Deal", 5000, 10, startsAt, endsAt))
        }.body<DealResponse>()

        val customerToken = mintCustomerToken()
        val claim = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "claim-fail-retry")
            contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(quantity = 1))
        }.body<ClaimResponse>()

        // 1. payment.failed arrives
        val failedPayload = WebhookPayload(
            event_id = "evt_fail_${UUID.randomUUID()}",
            type = "payment.failed",
            claim_id = claim.id,
            amount_paise = 5000,
            occurred_at = Clock.System.now()
        )
        val rawFailBody = json.encodeToString(failedPayload).toByteArray(Charsets.UTF_8)
        val failSig = webhookVerifier.sign(rawFailBody)

        val res1 = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", failSig)
            contentType(ContentType.Application.Json)
            setBody(rawFailBody)
        }
        assertEquals(HttpStatusCode.OK, res1.status)

        // Status remains RESERVED
        val claimAfterFail = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }.body<ClaimResponse>()
        assertEquals("RESERVED", claimAfterFail.status)

        // 2. Later, payment.succeeded arrives for the same claim
        val successPayload = WebhookPayload(
            event_id = "evt_succ_${UUID.randomUUID()}",
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 5000,
            occurred_at = Clock.System.now()
        )
        val rawSuccBody = json.encodeToString(successPayload).toByteArray(Charsets.UTF_8)
        val succSig = webhookVerifier.sign(rawSuccBody)

        val res2 = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", succSig)
            contentType(ContentType.Application.Json)
            setBody(rawSuccBody)
        }
        assertEquals(HttpStatusCode.OK, res2.status)

        // Status is now PAID!
        val claimAfterSuccess = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }.body<ClaimResponse>()
        assertEquals("PAID", claimAfterSuccess.status)
    }
}
