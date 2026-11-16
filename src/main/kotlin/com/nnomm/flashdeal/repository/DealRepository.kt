package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.domain.DealResponse
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

class DealRepository {

    fun createDeal(conn: Connection, restaurantId: UUID, request: CreateDealRequest): DealResponse {
        val dealId = UUID.randomUUID()
        val sql = """
            INSERT INTO deals (id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, dealId)
            stmt.setObject(2, restaurantId)
            stmt.setString(3, request.title)
            stmt.setInt(4, request.price_paise)
            stmt.setInt(5, request.stock_total)
            stmt.setInt(6, request.stock_total)
            stmt.setTimestamp(7, Timestamp.from(request.starts_at.toJavaInstant()))
            stmt.setTimestamp(8, Timestamp.from(request.ends_at.toJavaInstant()))

            val rs = stmt.executeQuery()
            if (rs.next()) {
                return DealResponse(
                    id = rs.getObject("id", UUID::class.java).toString(),
                    restaurant_id = rs.getObject("restaurant_id", UUID::class.java).toString(),
                    title = rs.getString("title"),
                    price_paise = rs.getInt("price_paise"),
                    stock_total = rs.getInt("stock_total"),
                    stock_available = rs.getInt("stock_available"),
                    starts_at = rs.getTimestamp("starts_at").toInstant().toKotlinInstant(),
                    ends_at = rs.getTimestamp("ends_at").toInstant().toKotlinInstant()
                )
            } else {
                error("Failed to insert deal")
            }
        }
    }

    fun getDealById(conn: Connection, dealId: UUID): DealResponse? {
        val sql = """
            SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at
            FROM deals
            WHERE id = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, dealId)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                return DealResponse(
                    id = rs.getObject("id", UUID::class.java).toString(),
                    restaurant_id = rs.getObject("restaurant_id", UUID::class.java).toString(),
                    title = rs.getString("title"),
                    price_paise = rs.getInt("price_paise"),
                    stock_total = rs.getInt("stock_total"),
                    stock_available = rs.getInt("stock_available"),
                    starts_at = rs.getTimestamp("starts_at").toInstant().toKotlinInstant(),
                    ends_at = rs.getTimestamp("ends_at").toInstant().toKotlinInstant()
                )
            }
            return null
        }
    }
}
