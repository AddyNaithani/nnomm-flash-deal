package com.nnomm.flashdeal.db

import com.nnomm.flashdeal.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    fun init(config: AppConfig): DataSource {
        if (dataSource != null) return dataSource!!

        val hikariConfig = HikariConfig().apply {
            // Support jdbc:postgresql://... as well as postgresql://...
            val rawUrl = config.databaseUrl
            jdbcUrl = if (!rawUrl.startsWith("jdbc:")) "jdbc:$rawUrl" else rawUrl
            username = config.databaseUser
            password = config.databasePassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 50
            minimumIdle = 5
            idleTimeout = 30000
            connectionTimeout = 20000
            maxLifetime = 600000
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        dataSource = HikariDataSource(hikariConfig)
        return dataSource!!
    }

    fun getDataSource(): DataSource {
        return dataSource ?: error("DatabaseFactory is not initialized")
    }

    suspend fun <T> inTransaction(
        isolationLevel: Int = Connection.TRANSACTION_READ_COMMITTED,
        block: (Connection) -> T
    ): T = withContext(Dispatchers.IO) {
        val conn = getDataSource().connection
        try {
            conn.autoCommit = false
            conn.transactionIsolation = isolationLevel
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Exception) {
            try {
                conn.rollback()
            } catch (rollbackEx: Exception) {
                e.addSuppressed(rollbackEx)
            }
            throw e
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun <T> inReadOnly(
        block: (Connection) -> T
    ): T = withContext(Dispatchers.IO) {
        val conn = getDataSource().connection
        try {
            conn.autoCommit = true
            conn.isReadOnly = true
            block(conn)
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {}
        }
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }
}
