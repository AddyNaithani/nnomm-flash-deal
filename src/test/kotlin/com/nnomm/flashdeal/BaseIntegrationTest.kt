package com.nnomm.flashdeal

import com.nnomm.flashdeal.config.AppConfig
import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.db.MigrationManager
import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.domain.DealResponse
import com.nnomm.flashdeal.domain.UserRole
import com.nnomm.flashdeal.security.JwtService
import com.nnomm.flashdeal.security.WebhookSignatureVerifier
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.time.Duration.Companion.hours

object TestDatabase {
    var container: PostgreSQLContainer<*>? = null
    var isInitialized = false
    var ready = false

    const val JWT_SECRET = "test-jwt-secret-key-32bytes-for-nnomm-flash-deal!"
    const val WEBHOOK_SECRET = "test-webhook-secret-key-32bytes-for-nnomm!"

    fun setup() {
        if (isInitialized) return
        isInitialized = true

        val envUrl = System.getenv("DATABASE_URL")
        val jdbcUrl: String
        val dbUser: String
        val dbPass: String

        if (!envUrl.isNullOrBlank()) {
            jdbcUrl = envUrl
            dbUser = System.getenv("DATABASE_USER") ?: "postgres"
            dbPass = System.getenv("DATABASE_PASSWORD") ?: "postgres"
            ready = true
        } else {
            try {
                val pg = PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("nnomm_test")
                    .withUsername("test")
                    .withPassword("test")
                pg.start()
                container = pg
                jdbcUrl = pg.jdbcUrl
                dbUser = pg.username
                dbPass = pg.password
                ready = true
            } catch (e: Throwable) {
                System.err.println("WARN: Docker/PostgreSQL not available in local test environment: ${e.message}")
                ready = false
                return
            }
        }

        val config = AppConfig(
            databaseUrl = jdbcUrl,
            databaseUser = dbUser,
            databasePassword = dbPass,
            jwtSecret = JWT_SECRET,
            webhookSecret = WEBHOOK_SECRET,
            claimTtlSeconds = 600
        )

        val ds = DatabaseFactory.init(config)
        MigrationManager.migrate(ds)
    }

    fun cleanTables() {
        if (!ready) return
        try {
            val conn = DatabaseFactory.getDataSource().connection
            conn.use { c ->
                c.autoCommit = true
                val stmt = c.createStatement()
                stmt.execute("TRUNCATE TABLE deals, claims, idempotency_keys, processed_webhook_events CASCADE;")
            }
        } catch (e: Exception) {
            System.err.println("Error cleaning tables: ${e.message}")
        }
    }
}

abstract class BaseIntegrationTest {

    val jwtService = JwtService(TestDatabase.JWT_SECRET)
    val webhookVerifier = WebhookSignatureVerifier(TestDatabase.WEBHOOK_SECRET)
    val json = Json { ignoreUnknownKeys = true }

    companion object {
        @JvmStatic
        @BeforeAll
        fun initDatabase() {
            TestDatabase.setup()
        }
    }

    @BeforeEach
    fun checkDbAndClean() {
        Assumptions.assumeTrue(TestDatabase.ready, "PostgreSQL database required for integration tests")
        TestDatabase.cleanTables()
    }

    fun mintCustomerToken(customerId: UUID = UUID.randomUUID()): String {
        return jwtService.generateToken(userId = customerId, role = UserRole.CUSTOMER)
    }

    fun mintAdminToken(adminId: UUID = UUID.randomUUID(), restaurantId: UUID = UUID.randomUUID()): String {
        return jwtService.generateToken(userId = adminId, role = UserRole.RESTAURANT_ADMIN, restaurantId = restaurantId)
    }

    fun defaultTestConfig(): AppConfig {
        return AppConfig(
            jwtSecret = TestDatabase.JWT_SECRET,
            webhookSecret = TestDatabase.WEBHOOK_SECRET,
            claimTtlSeconds = 600
        )
    }
}
