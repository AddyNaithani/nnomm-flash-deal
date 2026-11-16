package com.nnomm.flashdeal.config

data class AppConfig(
    val databaseUrl: String = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nnomm",
    val databaseUser: String = System.getenv("DATABASE_USER") ?: "postgres",
    val databasePassword: String = System.getenv("DATABASE_PASSWORD") ?: "postgres",
    val jwtSecret: String = System.getenv("JWT_SECRET") ?: "default-insecure-jwt-secret-key-32bytes-min!",
    val webhookSecret: String = System.getenv("WEBHOOK_SECRET") ?: "default-webhook-secret-key-32bytes-min!",
    val claimTtlSeconds: Long = (System.getenv("CLAIM_TTL_SECONDS") ?: "600").toLong(),
    val port: Int = (System.getenv("PORT") ?: "8080").toInt()
)
