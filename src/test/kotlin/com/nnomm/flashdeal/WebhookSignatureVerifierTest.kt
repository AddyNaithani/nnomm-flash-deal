package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.UnauthenticatedException
import com.nnomm.flashdeal.security.WebhookSignatureVerifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class WebhookSignatureVerifierTest {

    private val secret = "test-webhook-secret-key-32bytes-for-unit-testing!"
    private val verifier = WebhookSignatureVerifier(secret)

    @Test
    fun `valid signature passes verification`() {
        val payload = "{\"test\":\"data\"}".toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis() / 1000
        val signature = verifier.sign(payload, unixSeconds = now)

        assertDoesNotThrow {
            verifier.verify(signature, payload, currentUnixSeconds = now)
        }
    }

    @Test
    fun `tampered payload fails verification`() {
        val originalPayload = "{\"amount\":100}".toByteArray(Charsets.UTF_8)
        val tamperedPayload = "{\"amount\":999}".toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis() / 1000
        val signature = verifier.sign(originalPayload, unixSeconds = now)

        assertThrows<UnauthenticatedException> {
            verifier.verify(signature, tamperedPayload, currentUnixSeconds = now)
        }
    }

    @Test
    fun `stale timestamp over 300 seconds fails verification`() {
        val payload = "{\"test\":\"data\"}".toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis() / 1000
        val past = now - 301
        val signature = verifier.sign(payload, unixSeconds = past)

        assertThrows<UnauthenticatedException> {
            verifier.verify(signature, payload, currentUnixSeconds = now)
        }
    }

    @Test
    fun `future timestamp over 300 seconds fails verification`() {
        val payload = "{\"test\":\"data\"}".toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis() / 1000
        val future = now + 301
        val signature = verifier.sign(payload, unixSeconds = future)

        assertThrows<UnauthenticatedException> {
            verifier.verify(signature, payload, currentUnixSeconds = now)
        }
    }

    @Test
    fun `missing or malformed header fails verification`() {
        val payload = "{}".toByteArray(Charsets.UTF_8)

        assertThrows<UnauthenticatedException> {
            verifier.verify(null, payload)
        }
        assertThrows<UnauthenticatedException> {
            verifier.verify("", payload)
        }
        assertThrows<UnauthenticatedException> {
            verifier.verify("invalid-signature", payload)
        }
    }
}
