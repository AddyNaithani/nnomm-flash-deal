package com.nnomm.flashdeal.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CreateDealRequest(
    val title: String,
    val price_paise: Int,
    val stock_total: Int,
    val starts_at: Instant,
    val ends_at: Instant
)

@Serializable
data class DealResponse(
    val id: String,
    val restaurant_id: String,
    val title: String,
    val price_paise: Int,
    val stock_total: Int,
    val stock_available: Int,
    val starts_at: Instant,
    val ends_at: Instant
)
