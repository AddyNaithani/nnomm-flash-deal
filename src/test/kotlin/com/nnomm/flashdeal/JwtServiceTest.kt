package com.nnomm.flashdeal

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.flashdeal.domain.UnauthenticatedException
import com.nnomm.flashdeal.domain.UserRole
import com.nnomm.flashdeal.security.JwtService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals

class JwtServiceTest {

    private val secret = "test-jwt-secret-key-32bytes-for-unit-testing!"
    private val jwtService = JwtService(secret)

    @Test
    fun `valid customer token verifies properly`() {
        val userId = UUID.randomUUID()
        val token = jwtService.generateToken(userId, UserRole.CUSTOMER)
        val authUser = jwtService.verifyToken(token)

        assertEquals(userId, authUser.id)
        assertEquals(UserRole.CUSTOMER, authUser.role)
        assertEquals(null, authUser.restaurantId)
    }

    @Test
    fun `valid restaurant admin token verifies properly`() {
        val userId = UUID.randomUUID()
        val restaurantId = UUID.randomUUID()
        val token = jwtService.generateToken(userId, UserRole.RESTAURANT_ADMIN, restaurantId)
        val authUser = jwtService.verifyToken(token)

        assertEquals(userId, authUser.id)
        assertEquals(UserRole.RESTAURANT_ADMIN, authUser.role)
        assertEquals(restaurantId, authUser.restaurantId)
    }

    @Test
    fun `tampered token throws UnauthenticatedException`() {
        val userId = UUID.randomUUID()
        val token = jwtService.generateToken(userId, UserRole.CUSTOMER)
        val tampered = token.dropLast(5) + "abcde"

        assertThrows<UnauthenticatedException> {
            jwtService.verifyToken(tampered)
        }
    }

    @Test
    fun `expired token throws UnauthenticatedException`() {
        val userId = UUID.randomUUID()
        val token = jwtService.generateToken(userId, UserRole.CUSTOMER, ttlSeconds = -10)

        assertThrows<UnauthenticatedException> {
            jwtService.verifyToken(token)
        }
    }

    @Test
    fun `none algorithm token throws UnauthenticatedException`() {
        val noneToken = JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("role", "customer")
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.none())

        assertThrows<UnauthenticatedException> {
            jwtService.verifyToken(noneToken)
        }
    }
}
