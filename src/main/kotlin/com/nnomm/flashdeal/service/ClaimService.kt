package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import com.nnomm.flashdeal.repository.AcquireResult
import com.nnomm.flashdeal.repository.ClaimRepository
import com.nnomm.flashdeal.repository.IdempotencyRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class ClaimExecutionResult(
    val statusCode: Int,
    val body: String,
    val isReplayed: Boolean
)

class ClaimService(
    private val claimRepository: ClaimRepository = ClaimRepository(),
    private val idempotencyRepository: IdempotencyRepository = IdempotencyRepository(),
    private val claimTtlSeconds: Long = 600
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val idempotencyKeyRegex = Regex("^[A-Za-z0-9\\-_]{1,64}$")

    suspend fun claim(
        caller: AuthUser,
        dealIdStr: String,
        idempotencyKey: String?,
        quantity: Int
    ): ClaimExecutionResult {
        // 1. Role check
        if (caller.role != UserRole.CUSTOMER) {
            throw ForbiddenException("Only customers can claim deals")
        }

        // 2. Idempotency-Key validation
        if (idempotencyKey.isNullOrBlank() || !idempotencyKeyRegex.matches(idempotencyKey)) {
            throw IdempotencyKeyInvalidException()
        }

        // 3. Quantity validation
        if (quantity < 1 || quantity > 3) {
            throw ValidationException("quantity must be between 1 and 3")
        }

        // 4. Deal UUID validation
        val dealUuid = try {
            UUID.fromString(dealIdStr)
        } catch (_: Exception) {
            throw NotFoundException("Deal not found")
        }

        // Execution with transactional idempotency management
        return DatabaseFactory.inTransaction { conn ->
            val acquireResult = idempotencyRepository.acquireOrGetReplay(
                conn = conn,
                customerId = caller.id,
                key = idempotencyKey,
                dealId = dealUuid,
                quantity = quantity
            )

            if (acquireResult is AcquireResult.Replay) {
                return@inTransaction ClaimExecutionResult(
                    statusCode = acquireResult.statusCode,
                    body = acquireResult.body,
                    isReplayed = true
                )
            }

            // We acquired the in-progress slot. Execute claim creation.
            val attemptSavepoint = conn.setSavepoint("claim_attempt")
            try {
                val claim = claimRepository.createClaim(
                    conn = conn,
                    dealId = dealUuid,
                    customerId = caller.id,
                    quantity = quantity,
                    ttlSeconds = claimTtlSeconds
                )
                val responseJson = json.encodeToString(claim)
                idempotencyRepository.complete(
                    conn = conn,
                    customerId = caller.id,
                    key = idempotencyKey,
                    statusCode = 201,
                    responseBody = responseJson
                )
                ClaimExecutionResult(
                    statusCode = 201,
                    body = responseJson,
                    isReplayed = false
                )
            } catch (e: NotFoundException) {
                // 404 is NOT stored in idempotency
                conn.rollback(attemptSavepoint)
                idempotencyRepository.removeInProgress(conn, caller.id, idempotencyKey)
                throw e
            } catch (e: ApiException) {
                conn.rollback(attemptSavepoint)
                // 409 conflicts (SOLD_OUT, ALREADY_CLAIMED, DEAL_NOT_LIVE) ARE stored!
                if (e.statusCode.value == 409) {
                    val errorResponseJson = json.encodeToString(ErrorResponse(ErrorDetails(e.errorCode, e.message)))
                    idempotencyRepository.complete(
                        conn = conn,
                        customerId = caller.id,
                        key = idempotencyKey,
                        statusCode = 409,
                        responseBody = errorResponseJson
                    )
                } else {
                    idempotencyRepository.removeInProgress(conn, caller.id, idempotencyKey)
                }
                throw e
            } catch (e: Exception) {
                conn.rollback(attemptSavepoint)
                idempotencyRepository.removeInProgress(conn, caller.id, idempotencyKey)
                throw e
            }
        }
    }

    suspend fun getClaim(caller: AuthUser, claimIdStr: String): ClaimResponse {
        val claimUuid = try {
            UUID.fromString(claimIdStr)
        } catch (_: Exception) {
            throw NotFoundException("Claim not found")
        }

        return DatabaseFactory.inReadOnly { conn ->
            val record = claimRepository.getClaimById(conn, claimUuid)
                ?: throw NotFoundException("Claim not found")

            // Visibility rule: A claim is visible only to its customer and to the admin of the deal's restaurant.
            // Anyone else gets 404, not 403.
            val isOwnerCustomer = caller.role == UserRole.CUSTOMER && caller.id.toString() == record.claim.customer_id
            val isDealAdmin = caller.role == UserRole.RESTAURANT_ADMIN && caller.restaurantId == record.restaurantId

            if (!isOwnerCustomer && !isDealAdmin) {
                throw NotFoundException("Claim not found")
            }

            record.claim
        }
    }
}
