package io.realworld.app.domain.service

import io.realworld.app.domain.Article
import io.realworld.app.domain.ArticlesDTO
import io.realworld.app.domain.User
import io.realworld.app.domain.exceptions.NotFoundException
import io.realworld.app.domain.repository.ArticleRepository
import io.realworld.app.ext.toSlug

class ArticleService(private val articleRepository: ArticleRepository) {

    fun create(author: User, article: Article): Article {
        val id = articleRepository.create(author.id!!, uniqueSlug(article.title!!), article, System.currentTimeMillis())
        return articleRepository.findByIds(listOf(id), author.id).single()
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

    fun findPopular(viewer: User?, limit: Int, offset: Long): ArticlesDTO {
        val ids = articleRepository.findPopularIds(limit, offset)
        val articles = articleRepository.findByIds(ids, viewer?.id)
        // articlesCount is the total, not the page size, so clients can paginate (RealWorld spec).
        return ArticlesDTO(articles, articleRepository.countAll().toInt())
    }

    private fun articleIdOrThrow(slug: String): Long =
        articleRepository.findIdBySlug(slug) ?: throw NotFoundException("Article not found.")

    /** "slug test" -> "slug-test"; if taken, "slug-test-2", "slug-test-3", ... */
    private fun uniqueSlug(title: String): String {
        val base = title.toSlug()
        if (!articleRepository.slugExists(base)) return base
        var n = 2
        while (articleRepository.slugExists("$base-$n")) n++
        return "$base-$n"
    }
}
