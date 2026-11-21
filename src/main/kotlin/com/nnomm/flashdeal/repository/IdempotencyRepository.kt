package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.domain.IdempotencyKeyReusedException
import com.nnomm.flashdeal.domain.RequestInProgressException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class IdempotencyRecord(
    val customerId: UUID,
    val key: String,
    val dealId: UUID,
    val quantity: Int,
    val status: String, // IN_PROGRESS, COMPLETED
    val responseCode: Int?,
    val responseBody: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

sealed class AcquireResult {
    data object Acquired : AcquireResult()
    data class Replay(val statusCode: Int, val body: String) : AcquireResult()
}

class IdempotencyRepository {

    fun getRecord(conn: Connection, customerId: UUID, key: String): IdempotencyRecord? {
        val sql = """
            SELECT customer_id, idempotency_key, deal_id, quantity, status, response_code, response_body, created_at, updated_at
            FROM idempotency_keys
            WHERE customer_id = ? AND idempotency_key = ?
            FOR UPDATE
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                return IdempotencyRecord(
                    customerId = rs.getObject("customer_id", UUID::class.java),
                    key = rs.getString("idempotency_key"),
                    dealId = rs.getObject("deal_id", UUID::class.java),
                    quantity = rs.getInt("quantity"),
                    status = rs.getString("status"),
                    responseCode = rs.getObject("response_code") as? Int,
                    responseBody = rs.getString("response_body"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant()
                )
            }
            return null
        }
    }

    fun acquireOrGetReplay(
        conn: Connection,
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int
    ): AcquireResult {
        // Query with FOR UPDATE lock on the idempotency key if present
        val existing = getRecord(conn, customerId, key)

        if (existing == null) {
            val insertSql = """
                INSERT INTO idempotency_keys (customer_id, idempotency_key, deal_id, quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', NOW(), NOW())
                ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """.trimIndent()
            val inserted = conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, customerId)
                stmt.setString(2, key)
                stmt.setObject(3, dealId)
                stmt.setInt(4, quantity)
                stmt.executeUpdate()
            }
            if (inserted > 0) {
                return AcquireResult.Acquired
            }
            // Another concurrent transaction inserted the key at the same instant
            val concurrent = getRecord(conn, customerId, key)
            if (concurrent != null && concurrent.status == "COMPLETED") {
                if (concurrent.dealId != dealId || concurrent.quantity != quantity) {
                    throw IdempotencyKeyReusedException("Same key with different request parameters")
                }
                return AcquireResult.Replay(
                    statusCode = concurrent.responseCode ?: 201,
                    body = concurrent.responseBody ?: ""
                )
            }
            throw RequestInProgressException("A request with the same key is still running")
        }

        val now = Instant.now()
        // If older than 24 hours, key has expired from replay window; can be treated as new or reset
        val isWithin24Hours = existing.createdAt.isAfter(now.minusSeconds(24 * 3600))

        if (existing.status == "COMPLETED") {
            if (isWithin24Hours) {
                if (existing.dealId != dealId || existing.quantity != quantity) {
                    throw IdempotencyKeyReusedException("Same key with different request parameters")
                }
                return AcquireResult.Replay(
                    statusCode = existing.responseCode ?: 201,
                    body = existing.responseBody ?: ""
                )
            } else {
                // Past 24 hours: reset the key for a new request
                val updateSql = """
                    UPDATE idempotency_keys
                    SET deal_id = ?, quantity = ?, status = 'IN_PROGRESS', response_code = NULL, response_body = NULL, created_at = NOW(), updated_at = NOW()
                    WHERE customer_id = ? AND idempotency_key = ?
                """.trimIndent()
                conn.prepareStatement(updateSql).use { stmt ->
                    stmt.setObject(1, dealId)
                    stmt.setInt(2, quantity)
                    stmt.setObject(3, customerId)
                    stmt.setString(4, key)
                    stmt.executeUpdate()
                }
                return AcquireResult.Acquired
            }
        }

        // status == 'IN_PROGRESS'
        val isExpiredCrash = existing.updatedAt.isBefore(now.minusSeconds(60))
        if (isExpiredCrash) {
            // Previous execution crashed (> 60s ago); reclaim key
            val updateSql = """
                UPDATE idempotency_keys
                SET deal_id = ?, quantity = ?, status = 'IN_PROGRESS', updated_at = NOW()
                WHERE customer_id = ? AND idempotency_key = ?
            """.trimIndent()
            conn.prepareStatement(updateSql).use { stmt ->
                stmt.setObject(1, dealId)
                stmt.setInt(2, quantity)
                stmt.setObject(3, customerId)
                stmt.setString(4, key)
                stmt.executeUpdate()
            }
            return AcquireResult.Acquired
        } else {
            // Request is actively in progress
            throw RequestInProgressException("A request with the same key is still running")
        }
    }

    fun complete(
        conn: Connection,
        customerId: UUID,
        key: String,
        statusCode: Int,
        responseBody: String
    ) {
        val sql = """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_code = ?, response_body = ?, updated_at = NOW()
            WHERE customer_id = ? AND idempotency_key = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, statusCode)
            stmt.setString(2, responseBody)
            stmt.setObject(3, customerId)
            stmt.setString(4, key)
            stmt.executeUpdate()
        }
    }

    fun removeInProgress(conn: Connection, customerId: UUID, key: String) {
        val sql = "DELETE FROM idempotency_keys WHERE customer_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            stmt.executeUpdate()
        }
    }
}
