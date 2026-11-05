package io.realworld.app.web.rules

import io.realworld.app.config.SERVER_PORT
import io.realworld.app.config.setup
import io.realworld.app.web.util.HttpUtil
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.rules.ExternalResource
import java.net.Socket

/**
 * Gives each test a running server and an empty database.
 *
 * The previous rule built a new server, connection pool and H2 PG server for every single
 * test, which cannot work twice in one JVM (port already bound). The server now starts once
 * per test JVM and the database is wiped before each test, so tests can't see each other's
 * data and results don't depend on test order.
 */
class AppRule : ExternalResource() {
    lateinit var http: HttpUtil
    val port = SERVER_PORT

    override fun before() {
        TestServer.ensureStarted()
        TestDatabase.reset()
        http = HttpUtil(port)
    }
}

private object TestServer {
    private val app by lazy {
        setup().also { engine ->
            engine.start()
            Runtime.getRuntime().addShutdownHook(Thread { engine.stop(500, 500) })
            waitUntilListening(SERVER_PORT)
        }
    }

    fun ensureStarted() {
        app
    }

    private fun waitUntilListening(port: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("localhost", port).close()
                return
            } catch (e: Exception) {
                Thread.sleep(50)
            }
        }
        error("Server did not start listening on port $port within ${timeoutMs}ms")
    }
}

/** Empties every table without depending on the list of tables in main code. */
object TestDatabase {
    fun reset() = transaction {
        val tables = mutableListOf<String>()
        exec(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'"
        ) { rs ->
            while (rs.next()) tables += rs.getString(1)
        }
        exec("SET REFERENTIAL_INTEGRITY FALSE")
        try {
            tables.forEach { exec("TRUNCATE TABLE \"$it\" RESTART IDENTITY") }
        } finally {
            exec("SET REFERENTIAL_INTEGRITY TRUE")
        }
    }
}
