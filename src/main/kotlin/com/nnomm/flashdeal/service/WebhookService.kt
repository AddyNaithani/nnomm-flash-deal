package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.WebhookPayload
import com.nnomm.flashdeal.domain.WebhookResponse
import com.nnomm.flashdeal.repository.WebhookRepository
import com.nnomm.flashdeal.security.WebhookSignatureVerifier

class WebhookService(
    private val webhookRepository: WebhookRepository = WebhookRepository(),
    private val signatureVerifier: WebhookSignatureVerifier
) {

    fun verifySignature(signatureHeader: String?, rawBodyBytes: ByteArray) {
        signatureVerifier.verify(signatureHeader, rawBodyBytes)
    }

    suspend fun processPaymentWebhook(event: WebhookPayload): WebhookResponse {
        return DatabaseFactory.inTransaction { conn ->
            webhookRepository.processEvent(conn, event)
        }
    }
}
