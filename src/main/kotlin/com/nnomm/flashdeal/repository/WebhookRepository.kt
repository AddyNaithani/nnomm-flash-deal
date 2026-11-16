package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.domain.*
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class WebhookRepository {

    fun processEvent(
        conn: Connection,
        event: WebhookPayload
    ): WebhookResponse {
        val claimUuid = try {
            UUID.fromString(event.claim_id)
        } catch (_: Exception) {
            throw NotFoundException("Invalid claim UUID: ${event.claim_id}")
        }

        val now = Instant.now()
        val occurredInstant = java.time.Instant.ofEpochMilli(event.occurred_at.toEpochMilliseconds())

        // 1. Deduplication via processed_webhook_events
        val insertEventSql = """
            INSERT INTO processed_webhook_events (event_id, claim_id, event_type, amount_paise, occurred_at, processed_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()

        try {
            conn.prepareStatement(insertEventSql).use { stmt ->
                stmt.setString(1, event.event_id)
                stmt.setObject(2, claimUuid)
                stmt.setString(3, event.type)
                stmt.setInt(4, event.amount_paise)
                stmt.setTimestamp(5, Timestamp.from(occurredInstant))
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.executeUpdate()
            }
        } catch (e: PSQLException) {
            // Unique violation on primary key (event_id) -> already processed
            if (e.sqlState == "23505") {
                return WebhookResponse("DUPLICATE_IGNORED")
            }
            throw e
        }

        // 2. Lock the claim row FOR UPDATE
        val claimSql = """
            SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at
            FROM claims
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()

        val currentStatus: String
        val dealId: UUID
        val quantity: Int
        val expectedAmountPaise: Int
        val expiresAt: Instant

        conn.prepareStatement(claimSql).use { stmt ->
            stmt.setObject(1, claimUuid)
            val rs = stmt.executeQuery()
            if (!rs.next()) {
                // Unknown claim_id: we already recorded the event, return 404 as entity not found
                throw NotFoundException("Claim not found: $claimUuid")
            }
            currentStatus = rs.getString("status")
            dealId = rs.getObject("deal_id", UUID::class.java)
            quantity = rs.getInt("quantity")
            expectedAmountPaise = rs.getInt("amount_paise")
            expiresAt = rs.getTimestamp("expires_at").toInstant()
        }

        // 3. Handle payment.failed
        if (event.type == "payment.failed") {
            // No change; customer may pay again until expiry
            return WebhookResponse("PAYMENT_FAILED_RECORDED")
        }

        // 4. Handle payment.succeeded
        if (event.type == "payment.succeeded") {
            val isExpiredByTime = !now.isBefore(expiresAt)

            when {
                // Claim was already EXPIRED: units already returned to stock.
                currentStatus == ClaimStatus.EXPIRED.name -> {
                    updateClaimStatus(conn, claimUuid, ClaimStatus.REFUND_PENDING)
                    return WebhookResponse("REFUND_PENDING")
                }

                // Claim was RESERVED but expires_at has passed before payment processed:
                currentStatus == ClaimStatus.RESERVED.name && isExpiredByTime -> {
                    // Mark REFUND_PENDING and return units back to stock
                    updateClaimStatus(conn, claimUuid, ClaimStatus.REFUND_PENDING)
                    val restoreStockSql = "UPDATE deals SET stock_available = stock_available + ? WHERE id = ?"
                    conn.prepareStatement(restoreStockSql).use { stmt ->
                        stmt.setInt(1, quantity)
                        stmt.setObject(2, dealId)
                        stmt.executeUpdate()
                    }
                    return WebhookResponse("REFUND_PENDING")
                }

                // Claim is RESERVED and before expires_at:
                currentStatus == ClaimStatus.RESERVED.name && !isExpiredByTime -> {
                    if (event.amount_paise == expectedAmountPaise) {
                        updateClaimStatus(conn, claimUuid, ClaimStatus.PAID)
                        return WebhookResponse("PAID")
                    } else {
                        updateClaimStatus(conn, claimUuid, ClaimStatus.NEEDS_REVIEW)
                        return WebhookResponse("NEEDS_REVIEW")
                    }
                }

                // Claim already in a terminal/settled status (PAID, NEEDS_REVIEW, REFUND_PENDING) with new event_id
                else -> {
                    // Decide and document why: An overpayment / secondary capture.
                    // Keep the original claim status intact so units remain accounted for; log audit event.
                    return WebhookResponse("ALREADY_SETTLED")
                }
            }
        }

        return WebhookResponse("PROCESSED")
    }

    private fun updateClaimStatus(conn: Connection, claimId: UUID, status: ClaimStatus) {
        val sql = "UPDATE claims SET status = ?, updated_at = NOW() WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, status.name)
            stmt.setObject(2, claimId)
            stmt.executeUpdate()
        }
    }
}
