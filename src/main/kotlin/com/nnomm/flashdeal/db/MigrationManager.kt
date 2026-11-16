package com.nnomm.flashdeal.db

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object MigrationManager {
    fun migrate(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()
    }
}
