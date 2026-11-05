package io.realworld.app.web.controllers

import io.ktor.application.ApplicationCall
import io.ktor.auth.authentication
import io.ktor.request.receive
import io.ktor.response.respond
import io.realworld.app.domain.ArticleDTO
import io.realworld.app.domain.ArticlesDTO
import io.realworld.app.domain.User
import io.realworld.app.domain.service.ArticleService

// findBy, feed, get, update and delete are still the original stubs. Only the endpoints the
// popular feed depends on (create, favorite, unfavorite) are implemented; see docs/popular-articles.md.
class ArticleController(private val articleService: ArticleService) {

    /** GET /api/articles/feed/popular. Login optional; limit 1..100 (default 20), offset >= 0. */
    suspend fun popular(ctx: ApplicationCall) {
        val limit = ctx.intParam("limit", default = 20, min = 1, max = 100)
        // Long, not Int: an offset past the end is an empty page however large it is (R5).
        val offset = ctx.longParam("offset", default = 0, min = 0, max = Long.MAX_VALUE)
        ctx.respond(articleService.findPopular(ctx.authentication.principal<User>(), limit, offset))
    }

    private fun ApplicationCall.intParam(name: String, default: Int, min: Int, max: Int): Int =
        longParam(name, default.toLong(), min.toLong(), max.toLong()).toInt()

    private fun ApplicationCall.longParam(name: String, default: Long, min: Long, max: Long): Long {
        val raw = parameters[name] ?: return default
        val value = raw.toLongOrNull()
        require(value != null && value in min..max) {
            if (max == Long.MAX_VALUE) "$name must be a whole number of $min or more."
            else "$name must be a whole number from $min to $max."
        }
        return value
    }

    fun findBy(ctx: ApplicationCall): ArticlesDTO {
        val tag = ctx.parameters["tag"]
        val author = ctx.parameters["author"]
        val favorited = ctx.parameters["favorited"]
        val limit = ctx.parameters["limit"] ?: "20"
        val offset = ctx.parameters["offset"] ?: "0"
//        articleService.findBy(tag, author, favorited, limit.toInt(), offset.toInt()).also { articles ->
//            ctx.json(ArticlesDTO(articles, articles.size))
//        }
        return ArticlesDTO(listOf(), 1)
    }

    fun feed(ctx: ApplicationCall): ArticlesDTO {
        val limit = ctx.parameters["limit"] ?: "20"
        val offset = ctx.parameters["offset"] ?: "0"
//        articleService.findFeed(ctx.attribute("email"), limit.toInt(), offset.toInt()).also { articles ->
//            ctx.json(ArticlesDTO(articles, articles.size))
//        }
        return ArticlesDTO(listOf(), 1)
    }

    fun get(ctx: ApplicationCall): ArticleDTO {
        ctx.parameters["slug"]
        //                articleService.findBySlug(slug).apply {
//                    ctx.json(ArticleDTO(this))
//                }
        return ArticleDTO(null)
    }

    suspend fun create(ctx: ApplicationCall) {
        val article = ctx.receive<ArticleDTO>().validCreate()
        ctx.respond(ArticleDTO(articleService.create(ctx.currentUser(), article)))
    }

    suspend fun update(ctx: ApplicationCall): ArticleDTO {
        val slug = ctx.parameters["slug"]
        ctx.receive<ArticleDTO>()
        //            articleService.update(slug, article).apply {
//                ctx.json(ArticleDTO(this))
//            }
        return ArticleDTO(null)
    }

    fun delete(ctx: ApplicationCall) {
        ctx.parameters["slug"]
        //            articleService.delete(slug)
    }

    suspend fun favorite(ctx: ApplicationCall) {
        ctx.respond(ArticleDTO(articleService.favorite(ctx.currentUser(), ctx.parameters["slug"]!!)))
    }

    suspend fun unfavorite(ctx: ApplicationCall) {
        ctx.respond(ArticleDTO(articleService.unfavorite(ctx.currentUser(), ctx.parameters["slug"]!!)))
    }

    // Only called on routes wrapped in a required authenticate {} block.
    private fun ApplicationCall.currentUser(): User =
        authentication.principal() ?: error("Route must be wrapped in authenticate {}")
}
