package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.domain.*
import kotlinx.datetime.toKotlinInstant
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ClaimWithDealAdmin(
    val claim: ClaimResponse,
    val restaurantId: UUID
)

class ClaimRepository {

    fun createClaim(
        conn: Connection,
        dealId: UUID,
        customerId: UUID,
        quantity: Int,
        ttlSeconds: Long
    ): ClaimResponse {
        // 1. Lock the deal row FOR UPDATE to safely verify stock and live window
        val dealSql = """
            SELECT price_paise, stock_available, starts_at, ends_at
            FROM deals
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()

        val pricePaise: Int
        val stockAvailable: Int
        val startsAt: Instant
        val endsAt: Instant

        conn.prepareStatement(dealSql).use { stmt ->
            stmt.setObject(1, dealId)
            val rs = stmt.executeQuery()
            if (!rs.next()) {
                throw NotFoundException("Deal not found: $dealId")
            }
            pricePaise = rs.getInt("price_paise")
            stockAvailable = rs.getInt("stock_available")
            startsAt = rs.getTimestamp("starts_at").toInstant()
            endsAt = rs.getTimestamp("ends_at").toInstant()
        }

        // 2. Validate live window
        val now = Instant.now()
        if (now.isBefore(startsAt) || !now.isBefore(endsAt)) {
            throw DealNotLiveException("Deal is not currently live")
        }

        // 3. Validate stock availability
        if (stockAvailable < quantity) {
            throw SoldOutException("Not enough units available")
        }

        // 4. Decrement stock
        val updateStockSql = "UPDATE deals SET stock_available = stock_available - ? WHERE id = ?"
        conn.prepareStatement(updateStockSql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            stmt.executeUpdate()
        }

        // 5. Insert claim with status RESERVED
        val claimId = UUID.randomUUID()
        val amountPaise = pricePaise * quantity
        val expiresAt = now.plusSeconds(ttlSeconds)

        val insertClaimSql = """
            INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?)
        """.trimIndent()

        val savepoint = conn.setSavepoint("claim_insert")
        try {
            conn.prepareStatement(insertClaimSql).use { stmt ->
                stmt.setObject(1, claimId)
                stmt.setObject(2, dealId)
                stmt.setObject(3, customerId)
                stmt.setInt(4, quantity)
                stmt.setInt(5, amountPaise)
                stmt.setTimestamp(6, Timestamp.from(expiresAt))
                stmt.setTimestamp(7, Timestamp.from(now))
                stmt.setTimestamp(8, Timestamp.from(now))
                stmt.executeUpdate()
            }
        } catch (e: PSQLException) {
            conn.rollback(savepoint)
            // Check for unique index violation (idx_claims_active_per_customer_deal)
            if (e.sqlState == "23505") {
                throw AlreadyClaimedException("Customer already has an active claim on this deal")
            }
            throw e
        }

        return ClaimResponse(
            id = claimId.toString(),
            deal_id = dealId.toString(),
            customer_id = customerId.toString(),
            quantity = quantity,
            amount_paise = amountPaise,
            status = ClaimStatus.RESERVED.name,
            expires_at = expiresAt.toKotlinInstant()
        )
    }

    fun getClaimById(conn: Connection, claimId: UUID): ClaimWithDealAdmin? {
        val sql = """
            SELECT c.id, c.deal_id, c.customer_id, c.quantity, c.amount_paise, c.status, c.expires_at, d.restaurant_id
            FROM claims c
            JOIN deals d ON c.deal_id = d.id
            WHERE c.id = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, claimId)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val dbStatus = rs.getString("status")
                val expiresAt = rs.getTimestamp("expires_at").toInstant()
                val now = Instant.now()

                // If claim is RESERVED and expires_at has passed, the API never displays it as RESERVED
                val displayStatus = if (dbStatus == ClaimStatus.RESERVED.name && !now.isBefore(expiresAt)) {
                    ClaimStatus.EXPIRED.name
                } else {
                    dbStatus
                }

                val claim = ClaimResponse(
                    id = rs.getObject("id", UUID::class.java).toString(),
                    deal_id = rs.getObject("deal_id", UUID::class.java).toString(),
                    customer_id = rs.getObject("customer_id", UUID::class.java).toString(),
                    quantity = rs.getInt("quantity"),
                    amount_paise = rs.getInt("amount_paise"),
                    status = displayStatus,
                    expires_at = expiresAt.toKotlinInstant()
                )
                val restaurantId = rs.getObject("restaurant_id", UUID::class.java)
                return ClaimWithDealAdmin(claim, restaurantId)
            }
            return null
        }
    }

    fun expireReservedClaims(conn: Connection, batchSize: Int = 100): Int {
        val sql = """
            WITH expired_batch AS (
                SELECT id, deal_id, quantity
                FROM claims
                WHERE status = 'RESERVED' AND expires_at <= NOW()
                ORDER BY expires_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            ),
            updated_claims AS (
                UPDATE claims c
                SET status = 'EXPIRED', updated_at = NOW()
                FROM expired_batch eb
                WHERE c.id = eb.id
                RETURNING eb.id, eb.deal_id, eb.quantity
            ),
            restored_stock AS (
                UPDATE deals d
                SET stock_available = d.stock_available + aggregated.total_quantity
                FROM (
                    SELECT deal_id, SUM(quantity) as total_quantity
                    FROM updated_claims
                    GROUP BY deal_id
                ) aggregated
                WHERE d.id = aggregated.deal_id
            )
            SELECT COUNT(*) as expired_count FROM updated_claims
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, batchSize)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                return rs.getInt("expired_count")
            }
            return 0
        }
    }
}
