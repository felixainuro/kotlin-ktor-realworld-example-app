package io.realworld.app.web.controllers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mashape.unirest.http.Unirest
import io.realworld.app.domain.repository.Users
import io.realworld.app.utils.JwtProvider
import io.realworld.app.web.rules.AppRule
import io.realworld.app.web.util.HttpUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One test per defect the cold review reproduced over HTTP (see AGENT_WORKLOG.md). Each test
 * uses the reviewer's own trigger, so it would have failed before the fix.
 */
class ReviewFindingsTest {
    @Rule
    @JvmField
    val appRule = AppRule()

    private val json = jacksonObjectMapper()
    private val api get() = "http://localhost:${appRule.port}/api"

    // Finding 1: changing the password stored it unhashed and locked the user out.
    @Test
    fun `changing the password keeps the user able to log in with the new one only`() {
        register("pat@example.com", "pat", "secret1")
        val token = login("pat@example.com", "secret1").path("user").path("token").asText()

        val update = send("PUT", "/user", """{"user":{"email":"pat@example.com","password":"newpass"}}""", token)
        assertEquals(update.second.toString(), 200, update.first)
        assertFalse("update must return a usable token: ${update.second}", update.second.path("user").path("token").isNull)

        assertEquals(200, login("pat@example.com", "newpass", expect = null).path("_status").asInt())
        assertEquals(401, login("pat@example.com", "secret1", expect = null).path("_status").asInt())
    }

    // Finding 2: register echoed the plain password; login and current user returned the hash.
    @Test
    fun `no user response carries the password or its hash`() {
        val registered = register("sam@example.com", "sam", "s3cret-pass")
        val loggedIn = login("sam@example.com", "s3cret-pass")
        val token = loggedIn.path("user").path("token").asText()
        val current = send("GET", "/user", null, token).second
        val updated = send("PUT", "/user", """{"user":{"email":"sam@example.com","bio":"hi"}}""", token).second

        for ((name, body) in listOf("register" to registered, "login" to loggedIn, "current" to current, "update" to updated)) {
            val password = body.path("user").path("password")
            assertTrue("$name response has a password value: $body", password.isMissingNode || password.isNull)
            assertFalse("$name response contains the plain password: $body", body.toString().contains("s3cret-pass"))
        }
    }

    // Finding 8: a taken username hit the unique index and returned 500.
    @Test
    fun `registering a taken username gives 422`() {
        register("first@example.com", "taken", "pw")
        val (status, body) = send("POST", "/users", """{"user":{"email":"second@example.com","password":"pw","username":"taken"}}""")
        assertEquals(body.toString(), 422, status)
        assertEquals("Username already taken!", body.path("errors").path("body").path(0).asText())
    }

    // Finding 3: two creates with the same title at the same moment returned 500.
    @Test
    fun `simultaneous creates with the same title all succeed with distinct slugs`() {
        register("racer@example.com", "racer", "pw")
        val token = login("racer@example.com", "pw").path("user").path("token").asText()
        val parallel = 8
        val pool = Executors.newFixedThreadPool(parallel)
        val start = CountDownLatch(1)
        try {
            val futures = (1..parallel).map {
                pool.submit<Pair<Int, JsonNode>> {
                    start.await()
                    send("POST", "/articles", """{"article":{"title":"Race Title","description":"d","body":"b","tagList":["race"]}}""", token)
                }
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            results.forEach { (status, body) -> assertEquals(body.toString(), 200, status) }
            val slugs = results.map { it.second.path("article").path("slug").asText() }
            assertEquals("slugs must be distinct: $slugs", parallel, slugs.toSet().size)
            slugs.forEach { assertTrue(it, Regex("race-title(-\\d+)?").matches(it)) }
        } finally {
            pool.shutdownNow()
        }
    }

    // Finding 5: offsets past Int range returned 422 instead of an empty page (R5).
    @Test
    fun `an offset beyond the 32-bit range is still just an empty page`() {
        val (status, body) = send("GET", "/articles/feed/popular?offset=2147483648", null)
        assertEquals(body.toString(), 200, status)
        assertEquals(0, body.path("articles").size())
    }

    // Finding 6: every non-Latin title collapsed to the slug "article".
    @Test
    fun `slugs keep letters from any script and fold Latin accents`() {
        register("writer@example.com", "writer", "pw")
        val token = login("writer@example.com", "pw").path("user").path("token").asText()
        val expected = mapOf(
            "Привет мир" to "привет-мир",
            "日本語のタイトル" to "日本語のタイトル",
            "Café au Lait!" to "cafe-au-lait",
            "Straße 2" to "straße-2"
        )
        for ((title, slug) in expected) {
            val (status, body) = send("POST", "/articles", """{"article":{"title":"$title","description":"d","body":"b"}}""", token)
            assertEquals(body.toString(), 200, status)
            assertEquals(slug, body.path("article").path("slug").asText())
        }
        // The non-Latin slug must work in a URL too.
        val (status, body) = send("POST", "/articles/${URLEncoder.encode("привет-мир", "UTF-8")}/favorite", null, token)
        assertEquals(body.toString(), 200, status)
        assertEquals(1, body.path("article").path("favoritesCount").asInt())
    }

    // Finding 7: a null tag crashed with a 500; an over-long tag leaked an internal message.
    @Test
    fun `bad tags give 422 with a plain message`() {
        register("tagger@example.com", "tagger", "pw")
        val token = login("tagger@example.com", "pw").path("user").path("token").asText()
        val longTag = "x".repeat(101)
        val cases = mapOf(
            """["ok", null]""" to "Tags must be strings.",
            """["$longTag"]""" to "Each tag must be 100 characters or fewer."
        )
        for ((tags, message) in cases) {
            val (status, body) = send("POST", "/articles", """{"article":{"title":"t","description":"d","body":"b","tagList":$tags}}""", token)
            assertEquals(body.toString(), 422, status)
            assertEquals(message, body.path("errors").path("body").path(0).asText())
        }
    }

    // Finding 11: GET /tags, the one endpoint that worked before the /api move, returned 404.
    @Test
    fun `the old tags path still answers`() {
        val response = Unirest.get("http://localhost:${appRule.port}/tags").asString()
        assertEquals(200, response.status)
        assertTrue(json.readTree(response.body).path("tags").isArray)
    }

    // Finding 4: the signing key was hard-coded in the source, so anyone could mint a token.
    @Test
    fun `a token signed with the old hard-coded key is rejected`() {
        register("victim@example.com", "victim", "pw")
        val forged = JWT.create()
            .withIssuer(JwtProvider.issuer)
            .withAudience(JwtProvider.audience)
            .withClaim("email", "victim@example.com")
            .sign(Algorithm.HMAC256("something-very-secret-here"))

        val (status, body) = send("POST", "/articles", """{"article":{"title":"t","description":"d","body":"b"}}""", forged)

        assertEquals(body.toString(), 401, status)
    }

    // Finding 2, second half: passwords were an unsalted HMAC under that same public key.
    @Test
    fun `passwords are stored as salted bcrypt hashes`() {
        register("one@example.com", "one", "same-password")
        register("two@example.com", "two", "same-password")
        val stored = transaction {
            listOf("one@example.com", "two@example.com").map { email ->
                Users.select { Users.email eq email }.single()[Users.password]
            }
        }
        stored.forEach { assertTrue("not a bcrypt hash: $it", it.startsWith("\$2a\$")) }
        assertTrue("same password must hash differently (salt): $stored", stored[0] != stored[1])
    }

    // Updating to someone else's email or username hit the unique index and returned 500.
    @Test
    fun `updating to a taken email or username gives 422`() {
        register("owner@example.com", "owner", "pw")
        register("other@example.com", "other", "pw")
        val token = login("other@example.com", "pw").path("user").path("token").asText()

        val email = send("PUT", "/user", """{"user":{"email":"owner@example.com"}}""", token)
        assertEquals(email.second.toString(), 422, email.first)
        assertEquals("Email already registered!", email.second.path("errors").path("body").path(0).asText())

        val name = send("PUT", "/user", """{"user":{"email":"other@example.com","username":"owner"}}""", token)
        assertEquals(name.second.toString(), 422, name.first)
        assertEquals("Username already taken!", name.second.path("errors").path("body").path(0).asText())

        val own = send("PUT", "/user", """{"user":{"email":"other@example.com","username":"other"}}""", token)
        assertEquals("keeping your own email and username is fine: ${own.second}", 200, own.first)
    }

    private fun register(email: String, username: String, password: String): JsonNode {
        val (status, body) = send("POST", "/users", """{"user":{"email":"$email","password":"$password","username":"$username"}}""")
        assertEquals(body.toString(), 200, status)
        return body
    }

    /** Logs in; with expect = null, returns the body with the status under "_status" instead of asserting. */
    private fun login(email: String, password: String, expect: Int? = 200): JsonNode {
        val (status, body) = send("POST", "/users/login", """{"user":{"email":"$email","password":"$password"}}""")
        if (expect != null) assertEquals(body.toString(), expect, status)
        return (if (body.isObject) body.deepCopy() else json.createObjectNode()).apply {
            (this as com.fasterxml.jackson.databind.node.ObjectNode).put("_status", status)
        }
    }

    private fun send(method: String, path: String, body: String?, token: String? = null): Pair<Int, JsonNode> {
        val request = when (method) {
            "GET" -> Unirest.get(api + path)
            "PUT" -> Unirest.put(api + path)
            else -> Unirest.post(api + path)
        }
        request.header("Content-Type", "application/json").header("Accept", "application/json")
        if (token != null) request.header("Authorization", "Token $token")
        val response = if (body != null && request is com.mashape.unirest.request.HttpRequestWithBody) {
            request.body(body).asString()
        } else {
            request.asString()
        }
        val parsed = response.body?.takeIf { it.isNotBlank() }?.let { json.readTree(it) } ?: json.createObjectNode()
        return response.status to parsed
    }
}
