package io.realworld.app.web.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mashape.unirest.http.HttpResponse
import io.realworld.app.web.rules.AppRule
import io.realworld.app.web.util.HttpUtil
import org.apache.http.HttpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Acceptance tests for GET /api/articles/feed/popular and the supporting article endpoints,
 * derived from docs/popular-articles.md (rules R1 to R13). Every test name carries the rule
 * IDs it checks; "Create", "Favorite" and "Unfavorite" tests cover the supporting endpoints.
 *
 * AppRule empties the database before each test, so every test starts with no users and no
 * articles. Articles are created one after another, so creation order is "older to newer";
 * the spec's final tie-break on the internal id keeps that order total without any sleeps.
 */
class PopularArticlesTest {
    @Rule
    @JvmField
    val appRule = AppRule()

    // ---------------------------------------------------------------- R11 empty database

    @Test
    fun `R11 empty database gives 200 with no articles and a count of 0`() {
        for (viewer in listOf(anonymous(), user("alice"))) {
            val page = viewer.popular()
            assertEquals(emptyList<String>(), page.slugs)
            assertEquals(0, page.articlesCount)
        }
    }

    // ---------------------------------------------------------------- R1 R2 R3 ordering

    @Test
    fun `R1 most favorited article comes first`() {
        val author = user("author")
        val fans = listOf(user("fan1"), user("fan2"), user("fan3"))
        val a = author.publish("alpha")
        val b = author.publish("bravo")
        val c = author.publish("charlie")
        fans.take(2).forEach { it.favorite(a) }
        fans.take(1).forEach { it.favorite(b) }
        fans.forEach { it.favorite(c) }

        val page = anonymous().popular()

        // Neither creation order (a, b, c) nor newest first (c, b, a).
        assertEquals(listOf(c, a, b), page.slugs)
        assertEquals(listOf(3L, 2L, 1L), page.counts)
    }

    @Test
    fun `R2 equal favorite counts put the newer article first`() {
        val author = user("author")
        val fan = user("fan1")
        val a = author.publish("alpha")
        val b = author.publish("bravo")
        val c = author.publish("charlie")
        listOf(a, b, c).forEach { fan.favorite(it) }

        assertEquals(listOf(c, b, a), anonymous().popular().slugs)
    }

    @Test
    fun `R3 articles with zero favorites are listed after every favorited one`() {
        val author = user("author")
        val fan = user("fan1")
        val older = author.publish("older favorite")
        fan.favorite(older)
        val newer = author.publish("newer unloved")
        val newest = author.publish("newest unloved")

        val page = anonymous().popular()

        // Zero-favorite articles are ranked, not filtered out, and keep the R2 tie order.
        assertEquals(listOf(older, newest, newer), page.slugs)
        assertEquals(listOf(1L, 0L, 0L), page.counts)
        assertEquals(3, page.articlesCount)
    }

    @Test
    fun `R1 R2 R3 full ranking sorts by count then newer first within each count`() {
        val expected = seedMixedPopularity()

        val page = anonymous().popular(limit = 100)

        assertEquals(expected, page.slugs)
        assertEquals(listOf(2L, 2L, 1L, 1L, 1L, 0L, 0L), page.counts)
    }

    // ---------------------------------------------------------------- R4 R5 R7 paging

    @Test
    fun `R2 R5 R7 pages are consecutive slices of one total order`() {
        val expected = seedMixedPopularity()

        for (limit in 1..4) {
            val pages = (0 until expected.size step limit).map { offset ->
                anonymous().popular(limit = limit, offset = offset)
            }
            pages.forEachIndexed { i, page ->
                assertEquals("articlesCount on page $i with limit $limit", expected.size, page.articlesCount)
            }
            // Equal to chunked() means every page is exactly the next slice: no overlap, no gap.
            assertEquals("pages with limit $limit", expected.chunked(limit), pages.map { it.slugs })
        }
    }

    @Test
    fun `R4 R7 limit defaults to 20 and articlesCount is still the total`() {
        val author = user("author")
        val all = (1..21).map { author.publish("story $it") }

        val page = anonymous().popular()

        assertEquals(20, page.slugs.size)
        assertEquals(20, page.slugs.toSet().size)
        assertTrue(all.containsAll(page.slugs))
        assertEquals(21, page.articlesCount)
    }

    @Test
    fun `R4 R5 R7 limit of 100 is accepted and the next page holds the rest`() {
        val author = user("author")
        val all = (1..101).map { author.publish("story $it") }

        val first = anonymous().popular(limit = 100)
        val second = anonymous().popular(limit = 100, offset = 100)

        assertEquals(100, first.slugs.size)
        assertEquals(1, second.slugs.size)
        assertEquals(all.toSet(), (first.slugs + second.slugs).toSet())
        assertEquals(101, first.articlesCount)
        assertEquals(101, second.articlesCount)
    }

    @Test
    fun `R5 offset defaults to 0`() {
        val author = user("author")
        val fan = user("fan1")
        val top = author.publish("alpha")
        author.publish("bravo")
        author.publish("charlie")
        fan.favorite(top)

        val withoutOffset = anonymous().popular(limit = 2)
        val withZeroOffset = anonymous().popular(limit = 2, offset = 0)

        assertEquals(withZeroOffset.slugs, withoutOffset.slugs)
        assertEquals(top, withoutOffset.slugs.first())
        assertEquals(2, withoutOffset.slugs.size)
    }

    @Test
    fun `R5 R7 offset at or past the end gives an empty page with the full count`() {
        val author = user("author")
        listOf("alpha", "bravo", "charlie").forEach { author.publish(it) }

        assertEquals(1, anonymous().popular(offset = 2).slugs.size)
        for (offset in listOf(3, 1000)) {
            val page = anonymous().popular(offset = offset)
            assertEquals("articles at offset $offset", emptyList<String>(), page.slugs)
            assertEquals("articlesCount at offset $offset", 3, page.articlesCount)
        }
    }

    // ---------------------------------------------------------------- R6 bad paging values

    @Test
    fun `R6 limit below 1 or above 100 gives 422 with the RealWorld error body`() {
        for (limit in listOf("0", "-1", "101", "100000000000")) {
            assertRealWorldError(HttpStatus.SC_UNPROCESSABLE_ENTITY, anonymous().popularResponse(limit = limit), "limit $limit")
        }
    }

    @Test
    fun `R6 non-numeric limit gives 422 with the RealWorld error body`() {
        for (limit in listOf("abc", "ten")) {
            assertRealWorldError(HttpStatus.SC_UNPROCESSABLE_ENTITY, anonymous().popularResponse(limit = limit), "limit $limit")
        }
    }

    @Test
    fun `R6 negative or non-numeric offset gives 422 with the RealWorld error body`() {
        for (offset in listOf("-1", "abc")) {
            assertRealWorldError(HttpStatus.SC_UNPROCESSABLE_ENTITY, anonymous().popularResponse(offset = offset), "offset $offset")
        }
    }

    @Test
    fun `R4 R6 non-integer limit or offset gives 422`() {
        // R4 and R5 say "integer"; a decimal is not a valid paging value.
        assertRealWorldError(HttpStatus.SC_UNPROCESSABLE_ENTITY, anonymous().popularResponse(limit = "1.5"), "limit 1.5")
        assertRealWorldError(HttpStatus.SC_UNPROCESSABLE_ENTITY, anonymous().popularResponse(offset = "2.5"), "offset 2.5")
    }

    // ---------------------------------------------------------------- R8 R9 favorited flag

    @Test
    fun `R8 anonymous callers get 200 and favorited false on every article`() {
        val author = user("author")
        val fan = user("fan1")
        val a = author.publish("alpha")
        val b = author.publish("bravo")
        fan.favorite(a)
        fan.favorite(b)

        val page = anonymous().popular()

        assertEquals(setOf(a, b), page.slugs.toSet())
        assertTrue(page.articles.none { it.favorited })
        assertEquals(listOf(1L, 1L), page.counts)
    }

    @Test
    fun `R9 favorited is true only for the articles the caller favorited`() {
        val carol = user("carol")
        val alice = user("alice")
        val bob = user("bob")
        val a = carol.publish("alpha")
        val b = carol.publish("bravo")
        val c = carol.publish("charlie")
        val d = carol.publish("delta")
        alice.favorite(a)
        bob.favorite(b)
        alice.favorite(c)
        bob.favorite(c)

        assertEquals(mapOf(a to true, b to false, c to true, d to false), favoritedFlags(alice))
        assertEquals(mapOf(a to false, b to true, c to true, d to false), favoritedFlags(bob))
        assertEquals(mapOf(a to false, b to false, c to false, d to false), favoritedFlags(carol))
    }

    // ---------------------------------------------------------------- R10 counting

    @Test
    fun `R10 favoriting twice counts once and distinct users each count`() {
        val author = user("author")
        val alice = user("alice")
        val bob = user("bob")
        val carol = user("carol")
        val twoFans = author.publish("bravo")
        val oneFanTwice = author.publish("alpha") // newer: would win a 2-2 tie if double counted
        alice.favorite(oneFanTwice)
        alice.favorite(oneFanTwice)
        bob.favorite(twoFans)
        carol.favorite(twoFans)

        val page = anonymous().popular()

        assertEquals(listOf(twoFans, oneFanTwice), page.slugs)
        assertEquals(listOf(2L, 1L), page.counts)
    }

    @Test
    fun `R10 R9 unfavoriting lowers the count and can change the order`() {
        val author = user("author")
        val alice = user("alice")
        val bob = user("bob")
        val carol = user("carol")
        val a = author.publish("alpha")
        val b = author.publish("bravo")
        alice.favorite(a)
        bob.favorite(a)
        carol.favorite(b)
        assertEquals(listOf(a, b), anonymous().popular().slugs)

        alice.unfavorite(a)
        bob.unfavorite(a)

        val page = anonymous().popular()
        assertEquals(listOf(b, a), page.slugs)
        assertEquals(listOf(1L, 0L), page.counts)
        assertEquals(false, favoritedFlags(alice)[a])
    }

    // ---------------------------------------------------------------- R12 no data leaks

    @Test
    fun `R12 author is a profile and no email password or token appears anywhere`() {
        val alice = HttpUtil(appRule.port)
        val registered = alice.createUser("alice@example.com", "alice")
        val bob = user("bob")
        val slug = alice.publish("alpha")
        bob.favorite(slug)
        val secrets = listOfNotNull(
            "alice@example.com",
            "bob@example.com",
            registered.user?.token,
            alice.token(),
            bob.token()
        ).filter { it.isNotBlank() }

        for (viewer in listOf(anonymous(), alice, bob)) {
            val page = viewer.popular()
            val author = page.articles.single().author

            assertEquals("alice", author.path("username").asText())
            val allowed = setOf("username", "bio", "image", "following")
            val extra = author.fieldNames().asSequence().toSet() - allowed
            assertTrue("author has non-profile fields $extra: $author", extra.isEmpty())
            assertTrue("author.following must be false: $author", author.path("following").isBoolean)
            assertFalse(author.path("following").asBoolean())

            val leakedKeys = allFieldNames(page.json).intersect(setOf("email", "password", "token"))
            assertTrue("response has fields $leakedKeys: ${page.raw}", leakedKeys.isEmpty())
            secrets.forEach { assertFalse("response contains $it: ${page.raw}", page.raw.contains(it)) }
        }
    }

    // ---------------------------------------------------------------- R13 existing feed

    @Test
    fun `R13 existing feed still requires login`() {
        val author = user("author")
        author.publish("alpha") // so a mis-routed popular list would have something to show

        val response = anonymous().get<String>("/api/articles/feed")

        assertEquals("GET /api/articles/feed anonymously, body: ${response.body}", HttpStatus.SC_UNAUTHORIZED, response.status)
    }

    // ---------------------------------------------------------------- Create (supporting endpoint)

    @Test
    fun `Create returns the article with its fields author and no favorites`() {
        val alice = user("alice")
        val request = mapOf(
            "article" to mapOf(
                "title" to "How to train your dragon",
                "description" to "Ever wonder how?",
                "body" to "Very carefully.",
                "tagList" to listOf("dragons", "training")
            )
        )

        val response = alice.post<String>("/api/articles", request)

        assertSuccess("create", response)
        val article = mapper.readTree(response.body).path("article")
        assertEquals("How to train your dragon", article.path("title").asText())
        assertEquals("Ever wonder how?", article.path("description").asText())
        assertEquals("Very carefully.", article.path("body").asText())
        assertEquals(listOf("dragons", "training"), article.path("tagList").map { it.asText() }.sorted())
        assertEquals("alice", article.path("author").path("username").asText())
        assertEquals(0L, article.path("favoritesCount").asLong(-1))
        assertEquals(false, article.path("favorited").asBoolean(true))
    }

    @Test
    fun `Create derives the slug from the title and tagList is optional`() {
        val slug = user("alice").publish("slug test") // publish() sends no tagList

        assertEquals("slug-test", slug)
        assertEquals(listOf("slug-test"), anonymous().popular().slugs)
    }

    @Test
    fun `Create adds a numeric suffix when the slug is already taken`() {
        val alice = user("alice")
        assertEquals("slug-test", alice.publish("slug test"))
        assertEquals("slug-test-2", alice.publish("slug test"))

        // Slugs are global (routes are /articles/{slug}), so another author's copy is suffixed too.
        val third = user("bob").publish("slug test")
        assertTrue("third slug was $third", third.matches(Regex("slug-test-\\d+")))
        assertFalse("third slug was $third", third in setOf("slug-test", "slug-test-2"))

        val page = anonymous().popular()
        assertEquals(setOf("slug-test", "slug-test-2", third), page.slugs.toSet())
        assertEquals(3, page.articlesCount)
    }

    @Test
    fun `Create without a title gives 422`() = assertCreateRejectedWithout("title")

    @Test
    fun `Create without a description gives 422`() = assertCreateRejectedWithout("description")

    @Test
    fun `Create without a body gives 422`() = assertCreateRejectedWithout("body")

    @Test
    fun `Create requires login`() {
        val response = anonymous().post<String>("/api/articles", articleRequest("alpha"))

        assertEquals("anonymous create, body: ${response.body}", HttpStatus.SC_UNAUTHORIZED, response.status)
        assertEquals(0, anonymous().popular().articlesCount)
    }

    // ---------------------------------------------------------------- Favorite and unfavorite

    @Test
    fun `Favorite is idempotent and returns the article`() {
        val author = user("author")
        val alice = user("alice")
        val slug = author.publish("alpha")

        for (attempt in 1..2) {
            val returned = alice.favorite(slug)
            assertEquals("slug, favorite #$attempt", slug, returned.slug)
            assertTrue("favorited, favorite #$attempt", returned.favorited)
            assertEquals("favoritesCount, favorite #$attempt", 1L, returned.favoritesCount)
        }
        assertEquals(listOf(1L), anonymous().popular().counts)
    }

    @Test
    fun `Unfavorite is idempotent returns the article and leaves other users favorites`() {
        val author = user("author")
        val alice = user("alice")
        val bob = user("bob")
        val slug = author.publish("alpha")
        alice.favorite(slug)
        bob.favorite(slug)

        // The second call unfavorites an article alice no longer has favorited.
        for (attempt in 1..2) {
            val returned = alice.unfavorite(slug)
            assertEquals("slug, unfavorite #$attempt", slug, returned.slug)
            assertFalse("favorited, unfavorite #$attempt", returned.favorited)
            assertEquals("favoritesCount, unfavorite #$attempt", 1L, returned.favoritesCount)
        }
        assertEquals(listOf(1L), anonymous().popular().counts)
        assertEquals(true, favoritedFlags(bob)[slug])
    }

    @Test
    fun `Favorite and unfavorite give 404 for an unknown slug`() {
        val alice = user("alice")
        alice.publish("alpha")

        val favorite = alice.post<String>("/api/articles/no-such-article/favorite")
        val unfavorite = alice.deleteWithResponseBody<String>("/api/articles/no-such-article/favorite")

        assertEquals("favorite unknown slug, body: ${favorite.body}", HttpStatus.SC_NOT_FOUND, favorite.status)
        assertEquals("unfavorite unknown slug, body: ${unfavorite.body}", HttpStatus.SC_NOT_FOUND, unfavorite.status)
    }

    @Test
    fun `Favorite and unfavorite require login and change nothing without it`() {
        val author = user("author")
        val fan = user("fan1")
        val slug = author.publish("alpha")

        val favorite = anonymous().post<String>("/api/articles/$slug/favorite")
        assertEquals("anonymous favorite, body: ${favorite.body}", HttpStatus.SC_UNAUTHORIZED, favorite.status)
        assertEquals(listOf(0L), anonymous().popular().counts)

        fan.favorite(slug)
        val unfavorite = anonymous().deleteWithResponseBody<String>("/api/articles/$slug/favorite")
        assertEquals("anonymous unfavorite, body: ${unfavorite.body}", HttpStatus.SC_UNAUTHORIZED, unfavorite.status)
        assertEquals(listOf(1L), anonymous().popular().counts)
    }

    // ================================================================ helpers

    private val mapper = jacksonObjectMapper()

    private data class ListedArticle(
        val slug: String,
        val favoritesCount: Long,
        val favorited: Boolean,
        val author: JsonNode
    )

    private data class Page(val articles: List<ListedArticle>, val articlesCount: Int, val json: JsonNode, val raw: String) {
        val slugs: List<String> get() = articles.map { it.slug }
        val counts: List<Long> get() = articles.map { it.favoritesCount }
    }

    private fun anonymous() = HttpUtil(appRule.port)

    /** A logged-in client. Email and username are both derived from [name], so keep names unique per test. */
    private fun user(name: String): HttpUtil = HttpUtil(appRule.port).apply {
        createUser("$name@example.com", name)
        assertFalse("test setup: login failed for $name", token().isNullOrBlank() || token() == "null")
    }

    private fun HttpUtil.token(): String? = headers["Authorization"]?.removePrefix("Token ")

    private fun articleRequest(title: String, without: String? = null): Map<String, Any> {
        val fields = mapOf("title" to title, "description" to "About $title", "body" to "Text of $title")
        return mapOf("article" to fields.filterKeys { it != without })
    }

    /** Creates an article as this user (no tagList) and returns the slug the server gave it. */
    private fun HttpUtil.publish(title: String): String {
        val response = post<String>("/api/articles", articleRequest(title))
        assertSuccess("create '$title'", response)
        val slug = parse(response.body).path("article").path("slug")
        assertTrue("create '$title' must return article.slug: ${response.body}", slug.isTextual && slug.asText().isNotBlank())
        return slug.asText()
    }

    private fun HttpUtil.favorite(slug: String): ListedArticle {
        val response = post<String>("/api/articles/$slug/favorite")
        assertSuccess("favorite $slug", response)
        return listed(parse(response.body).path("article"))
    }

    private fun HttpUtil.unfavorite(slug: String): ListedArticle {
        val response = deleteWithResponseBody<String>("/api/articles/$slug/favorite")
        assertSuccess("unfavorite $slug", response)
        return listed(parse(response.body).path("article"))
    }

    private fun HttpUtil.popularResponse(limit: Any? = null, offset: Any? = null): HttpResponse<String> {
        val params = mutableMapOf<String, Any>()
        limit?.let { params["limit"] = it }
        offset?.let { params["offset"] = it }
        return get<String>(POPULAR, params)
    }

    /** GET the popular list, require 200, and check the fields every test relies on are really there. */
    private fun HttpUtil.popular(limit: Any? = null, offset: Any? = null): Page {
        val response = popularResponse(limit, offset)
        assertEquals("GET $POPULAR limit=$limit offset=$offset, body: ${response.body}", HttpStatus.SC_OK, response.status)
        val raw = response.body ?: ""
        val json = parse(raw)
        val articles = json.path("articles")
        val count = json.path("articlesCount")
        assertTrue("'articles' must be an array: $raw", articles.isArray)
        assertTrue("'articlesCount' must be an integer: $raw", count.isIntegralNumber)
        return Page(articles.map { listed(it) }, count.asInt(), json, raw)
    }

    /** Reads one article object, failing if a field is missing rather than silently defaulting. */
    private fun listed(node: JsonNode): ListedArticle {
        assertTrue("article needs a text slug: $node", node.path("slug").isTextual)
        assertTrue("article needs an integer favoritesCount: $node", node.path("favoritesCount").isIntegralNumber)
        assertTrue("article needs a boolean favorited: $node", node.path("favorited").isBoolean)
        assertTrue("article needs an author object: $node", node.path("author").isObject)
        return ListedArticle(
            node.path("slug").asText(),
            node.path("favoritesCount").asLong(),
            node.path("favorited").asBoolean(),
            node.path("author")
        )
    }

    private fun favoritedFlags(viewer: HttpUtil): Map<String, Boolean> =
        viewer.popular().articles.associate { it.slug to it.favorited }

    /**
     * Seven articles s0..s6 created in that order, with favorite counts 1, 2, 1, 0, 1, 2, 0.
     * Returns the order R1 to R3 require: count descending, newer first within a count.
     * Tie groups straddle page boundaries for page sizes 2, 3 and 4.
     */
    private fun seedMixedPopularity(): List<String> {
        val author = user("author")
        val fans = listOf(user("fan1"), user("fan2"))
        val counts = listOf(1, 2, 1, 0, 1, 2, 0)
        val s = counts.indices.map { author.publish("story $it") }
        counts.forEachIndexed { i, n -> fans.take(n).forEach { it.favorite(s[i]) } }
        return listOf(s[5], s[1], s[4], s[2], s[0], s[6], s[3])
    }

    private fun assertCreateRejectedWithout(field: String) {
        val alice = user("alice")

        val response = alice.post<String>("/api/articles", articleRequest("alpha", without = field))

        assertEquals("create without $field, body: ${response.body}", HttpStatus.SC_UNPROCESSABLE_ENTITY, response.status)
        assertEquals("nothing is created without $field", 0, anonymous().popular().articlesCount)
    }

    private fun assertSuccess(what: String, response: HttpResponse<String>) {
        assertTrue("$what should succeed (2xx), was ${response.status}: ${response.body}", response.status in 200..299)
    }

    /** Status plus the RealWorld error shape {"errors":{"body":["..."]}} with at least one message. */
    private fun assertRealWorldError(status: Int, response: HttpResponse<String>, what: String) {
        assertEquals("$what: status, body: ${response.body}", status, response.status)
        val messages = parse(response.body).path("errors").path("body")
        assertTrue(
            "$what: body must be {\"errors\":{\"body\":[\"...\"]}}, was: ${response.body}",
            messages.isArray && messages.size() > 0 && messages.all { it.isTextual && it.asText().isNotBlank() }
        )
    }

    private fun parse(body: String?): JsonNode =
        if (body.isNullOrBlank()) MissingNode.getInstance() else mapper.readTree(body) ?: MissingNode.getInstance()

    private fun allFieldNames(node: JsonNode): Set<String> = when {
        node.isObject -> node.fields().asSequence().flatMap { (key, value) -> sequenceOf(key) + allFieldNames(value) }.toSet()
        node.isArray -> node.flatMap { allFieldNames(it) }.toSet()
        else -> emptySet()
    }

    private companion object {
        const val POPULAR = "/api/articles/feed/popular"
    }
}
