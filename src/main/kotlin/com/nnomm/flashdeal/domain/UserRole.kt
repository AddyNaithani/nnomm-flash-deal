package com.nnomm.flashdeal.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class UserRole(val roleName: String) {
    CUSTOMER("customer"),
    RESTAURANT_ADMIN("restaurant_admin");

    companion object {
        fun fromString(role: String): UserRole? = entries.find { it.roleName.equals(role, ignoreCase = true) }
    }
}

data class AuthUser(
    val id: UUID,
    val role: UserRole,
    val restaurantId: UUID?
)
