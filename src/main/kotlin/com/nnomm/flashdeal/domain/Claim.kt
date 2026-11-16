package com.nnomm.flashdeal.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

enum class ClaimStatus {
    RESERVED,
    PAID,
    NEEDS_REVIEW,
    EXPIRED,
    REFUND_PENDING
}

@Serializable
data class CreateClaimRequest(
    val quantity: Int
)

@Serializable
data class ClaimResponse(
    val id: String,
    val deal_id: String,
    val customer_id: String,
    val quantity: Int,
    val amount_paise: Int,
    val status: String,
    val expires_at: Instant
)
