package io.realworld.app.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.h2.tools.Server
import org.jetbrains.exposed.sql.Database

object DbConfig {
    fun setup(jdbcUrl: String, username: String, password: String) {
        // Debug aid: H2's PostgreSQL-protocol server on localhost:5435 (user sa, no password).
        // It used to start unconditionally, in production too. Now only when asked for.
        if (System.getenv("H2_PG_SERVER") == "true") Server.createPgServer().start()
        val config = HikariConfig().also { config ->
            config.jdbcUrl = jdbcUrl
            config.username = username
            config.password = password
        }
        Database.connect(HikariDataSource(config))
    }
}
