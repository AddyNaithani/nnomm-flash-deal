package com.nnomm.flashdeal.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.nnomm.flashdeal.domain.ApiException
import com.nnomm.flashdeal.domain.AuthUser
import com.nnomm.flashdeal.domain.UnauthenticatedException
import com.nnomm.flashdeal.domain.UserRole
import java.util.Date
import java.util.UUID

class JwtService(private val secret: String) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    fun verifyToken(token: String): AuthUser {
        try {
            // Strictly check header algorithm is HS256
            val decoded = JWT.decode(token)
            if (decoded.algorithm != "HS256") {
                throw UnauthenticatedException("Algorithm must be HS256")
            }

            val verified = verifier.verify(token)
            val sub = verified.subject ?: throw UnauthenticatedException("Missing sub claim")
            val userId = try {
                UUID.fromString(sub)
            } catch (_: Exception) {
                throw UnauthenticatedException("Invalid UUID in sub claim")
            }

            val roleClaim = verified.getClaim("role")?.asString()
                ?: throw UnauthenticatedException("Missing role claim")
            val role = UserRole.fromString(roleClaim)
                ?: throw UnauthenticatedException("Invalid role claim")

            val restaurantIdClaim = verified.getClaim("restaurant_id")?.asString()
            val restaurantId = if (!restaurantIdClaim.isNullOrBlank()) {
                try {
                    UUID.fromString(restaurantIdClaim)
                } catch (_: Exception) {
                    throw UnauthenticatedException("Invalid UUID in restaurant_id claim")
                }
            } else null

            if (role == UserRole.RESTAURANT_ADMIN && restaurantId == null) {
                throw UnauthenticatedException("restaurant_admin token missing restaurant_id claim")
            }

            return AuthUser(userId, role, restaurantId)
        } catch (e: ApiException) {
            throw e
        } catch (e: JWTVerificationException) {
            throw UnauthenticatedException("Invalid or expired token: ${e.message}")
        } catch (e: Exception) {
            throw UnauthenticatedException("Token parsing error: ${e.message}")
        }
    }

    fun generateToken(
        userId: UUID,
        role: UserRole,
        restaurantId: UUID? = null,
        ttlSeconds: Long = 3600
    ): String {
        val now = System.currentTimeMillis()
        val builder = JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", role.roleName)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + ttlSeconds * 1000))

        if (role == UserRole.RESTAURANT_ADMIN && restaurantId != null) {
            builder.withClaim("restaurant_id", restaurantId.toString())
        }

        return builder.sign(algorithm)
    }
}
