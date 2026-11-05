package io.realworld.app.domain

import java.util.*

data class ArticleDTO(val article: Article?) {
    fun validCreate(): Article {
        require(
            article != null &&
                !article.title.isNullOrBlank() &&
                !article.description.isNullOrBlank() &&
                article.body.isNotBlank()
        ) { "Article title, description and body are required." }
        require(article.title.length <= 255) { "Article title must be 255 characters or fewer." }
        // JSON can smuggle nulls into a List<String>; widen the type so the check is real.
        val tags: List<String?> = article.tagList
        require(tags.all { it != null }) { "Tags must be strings." }
        require(tags.all { it!!.trim().length <= 100 }) { "Each tag must be 100 characters or fewer." }
        return article
    }
}

data class ArticlesDTO(val articles: List<Article>, val articlesCount: Int)

data class Article(val slug: String? = null,
                   val title: String?,
                   val description: String?,
                   val body: String,
                   val tagList: List<String> = listOf(),
                   val createdAt: Date? = null,
                   val updatedAt: Date? = null,
                   val favorited: Boolean = false,
                   val favoritesCount: Long = 0,
                   val author: Profile? = null)