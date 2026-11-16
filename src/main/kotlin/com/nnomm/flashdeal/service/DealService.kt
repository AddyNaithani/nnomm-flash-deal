package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import com.nnomm.flashdeal.repository.DealRepository
import kotlinx.datetime.toJavaInstant
import java.util.UUID

class DealService(private val dealRepository: DealRepository = DealRepository()) {

    suspend fun createDeal(caller: AuthUser, request: CreateDealRequest): DealResponse {
        if (caller.role != UserRole.RESTAURANT_ADMIN) {
            throw ForbiddenException("Only restaurant admins can create deals")
        }
        val restaurantId = caller.restaurantId
            ?: throw ForbiddenException("Admin token missing restaurant_id")

        // Input limits validation
        if (request.title.isBlank() || request.title.length > 100) {
            throw ValidationException("Title must be between 1 and 100 characters")
        }
        if (request.price_paise < 1 || request.price_paise > 1_000_000) {
            throw ValidationException("price_paise must be between 1 and 1,000,000")
        }
        if (request.stock_total < 1 || request.stock_total > 10_000) {
            throw ValidationException("stock_total must be between 1 and 10,000")
        }
        if (!request.starts_at.toJavaInstant().isBefore(request.ends_at.toJavaInstant())) {
            throw ValidationException("starts_at must be before ends_at")
        }

        return DatabaseFactory.inTransaction { conn ->
            dealRepository.createDeal(conn, restaurantId, request)
        }
    }

    suspend fun getDeal(dealIdStr: String): DealResponse {
        val dealUuid = try {
            UUID.fromString(dealIdStr)
        } catch (_: Exception) {
            throw NotFoundException("Deal not found")
        }

        return DatabaseFactory.inReadOnly { conn ->
            dealRepository.getDealById(conn, dealUuid)
                ?: throw NotFoundException("Deal not found")
        }
    }
}
