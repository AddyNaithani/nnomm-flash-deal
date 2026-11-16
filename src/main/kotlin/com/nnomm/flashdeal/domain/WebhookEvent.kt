package com.nnomm.flashdeal.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WebhookPayload(
    val event_id: String,
    val type: String,
    val claim_id: String,
    val amount_paise: Int,
    val occurred_at: Instant
)

@Serializable
data class WebhookResponse(
    val status: String
)
