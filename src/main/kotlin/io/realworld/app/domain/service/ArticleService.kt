package io.realworld.app.domain.service

import io.realworld.app.domain.Article
import io.realworld.app.domain.ArticlesDTO
import io.realworld.app.domain.User
import io.realworld.app.domain.exceptions.NotFoundException
import io.realworld.app.domain.repository.ArticleRepository
import io.realworld.app.ext.toSlug
import org.jetbrains.exposed.exceptions.ExposedSQLException

class ArticleService(private val articleRepository: ArticleRepository) {

    fun create(author: User, article: Article): Article {
        // Two creates with the same title can pick the same free slug at the same moment. The
        // unique index rejects the loser; pick the next free slug and try again instead of a 500.
        // A tag created concurrently by two requests is covered by the same retry.
        repeat(MAX_CREATE_ATTEMPTS) { attempt ->
            try {
                val id = articleRepository.create(author.id!!, uniqueSlug(article.title!!), article, System.currentTimeMillis())
                return articleRepository.findByIds(listOf(id), author.id).single()
            } catch (e: ExposedSQLException) {
                if (e.sqlState != UNIQUE_VIOLATION || attempt == MAX_CREATE_ATTEMPTS - 1) throw e
            }
        }
        error("unreachable")
    }

    fun favorite(user: User, slug: String): Article {
        val articleId = articleIdOrThrow(slug)
        articleRepository.favorite(articleId, user.id!!)
        return articleRepository.findByIds(listOf(articleId), user.id).single()
    }

    fun unfavorite(user: User, slug: String): Article {
        val articleId = articleIdOrThrow(slug)
        articleRepository.unfavorite(articleId, user.id!!)
        return articleRepository.findByIds(listOf(articleId), user.id).single()
    }

    // One snapshot for the page and the total, so a favorite landing mid-request can't make the
    // counts on the page disagree with their order or with articlesCount.
    fun findPopular(viewer: User?, limit: Int, offset: Long): ArticlesDTO = articleRepository.consistentRead {
        val ids = articleRepository.findPopularIds(limit, offset)
        val articles = articleRepository.findByIds(ids, viewer?.id)
        // articlesCount is the total, not the page size, so clients can paginate (RealWorld spec).
        ArticlesDTO(articles, articleRepository.countAll().toInt())
    }

    private fun articleIdOrThrow(slug: String): Long =
        articleRepository.findIdBySlug(slug) ?: throw NotFoundException("Article not found.")

    /** "slug test" -> "slug-test"; if taken, "slug-test-2", "slug-test-3", ... One query. */
    private fun uniqueSlug(title: String): String {
        val base = title.toSlug()
        val taken = articleRepository.slugsLike(base)
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private companion object {
        const val MAX_CREATE_ATTEMPTS = 10
        const val UNIQUE_VIOLATION = "23505" // SQLSTATE for a unique constraint violation
    }
}
