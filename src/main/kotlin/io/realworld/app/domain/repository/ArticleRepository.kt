package io.realworld.app.domain.repository

import io.realworld.app.domain.Article
import io.realworld.app.domain.Profile
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Date

internal object Articles : LongIdTable() {
    val slug: Column<String> = varchar("slug", 255).uniqueIndex()
    val title: Column<String> = varchar("title", 255)
    val description: Column<String> = text("description")
    val body: Column<String> = text("body")
    val author: Column<EntityID<Long>> = reference("author_id", Users)

    // Epoch milliseconds. Avoids pulling in an extra Exposed date module for two columns.
    val createdAt: Column<Long> = long("created_at")
    val updatedAt: Column<Long> = long("updated_at")
}

internal object ArticleTags : Table("article_tags") {
    val article: Column<EntityID<Long>> = reference("article_id", Articles)
    val tag: Column<EntityID<Long>> = reference("tag_id", Tags)
    override val primaryKey = PrimaryKey(article, tag)
}

internal object Favorites : Table() {
    val article: Column<EntityID<Long>> = reference("article_id", Articles)
    val user: Column<EntityID<Long>> = reference("user_id", Users)
    override val primaryKey = PrimaryKey(article, user)
}

class ArticleRepository {
    init {
        transaction {
            // Users and Tags are listed so this works whichever repository Kodein builds first.
            SchemaUtils.create(Users, Tags, Articles, ArticleTags, Favorites)
        }
    }

    fun create(authorId: Long, slug: String, article: Article, now: Long): Long = transaction {
        val articleId = Articles.insertAndGetId { row ->
            row[Articles.slug] = slug
            row[title] = article.title!!
            row[description] = article.description!!
            row[body] = article.body
            row[author] = EntityID(authorId, Users)
            row[createdAt] = now
            row[updatedAt] = now
        }
        article.tagList.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { name ->
            val tagId = Tags.select { Tags.name eq name }.map { it[Tags.id] }.firstOrNull()
                ?: Tags.insertAndGetId { it[Tags.name] = name }
            ArticleTags.insert { row ->
                row[ArticleTags.article] = articleId
                row[tag] = tagId
            }
        }
        articleId.value
    }

    fun slugExists(slug: String): Boolean = transaction {
        Articles.select { Articles.slug eq slug }.count() > 0
    }

    fun findIdBySlug(slug: String): Long? = transaction {
        Articles.select { Articles.slug eq slug }.map { it[Articles.id].value }.firstOrNull()
    }

    fun favorite(articleId: Long, userId: Long) = transaction {
        val already = Favorites.select {
            (Favorites.article eq articleId) and (Favorites.user eq userId)
        }.count() > 0
        if (!already) {
            Favorites.insert { row ->
                row[article] = EntityID(articleId, Articles)
                row[user] = EntityID(userId, Users)
            }
        }
    }

    fun unfavorite(articleId: Long, userId: Long) = transaction {
        Favorites.deleteWhere { (Favorites.article eq articleId) and (Favorites.user eq userId) }
    }

    fun countAll(): Long = transaction { Articles.selectAll().count() }

    /**
     * One page of article ids, most favorited first. Ties go to the newer article, then the
     * higher id, so the order is total and consecutive pages never overlap or skip.
     * The LEFT JOIN keeps articles nobody has favorited (COUNT of a NULL user is 0).
     */
    fun findPopularIds(limit: Int, offset: Long): List<Long> = transaction {
        val favoritesCount = Favorites.user.count()
        Articles.join(Favorites, JoinType.LEFT, Articles.id, Favorites.article)
            .slice(Articles.id, Articles.createdAt, favoritesCount)
            .selectAll()
            .groupBy(Articles.id, Articles.createdAt)
            .orderBy(
                favoritesCount to SortOrder.DESC,
                Articles.createdAt to SortOrder.DESC,
                Articles.id to SortOrder.DESC
            )
            .limit(limit, offset)
            .map { it[Articles.id].value }
    }

    /**
     * Full articles for the given ids, in the same order. Uses a fixed number of queries
     * (articles + authors, tags, favorite counts, viewer's favorites) whatever the page size.
     */
    fun findByIds(ids: List<Long>, viewerId: Long?): List<Article> = transaction {
        if (ids.isEmpty()) return@transaction emptyList<Article>()

        val rows = Articles.join(Users, JoinType.INNER, Articles.author, Users.id)
            .select { Articles.id inList ids }
            .associateBy { it[Articles.id].value }

        val tagsByArticle = ArticleTags.join(Tags, JoinType.INNER, ArticleTags.tag, Tags.id)
            .select { ArticleTags.article inList ids }
            .groupBy({ it[ArticleTags.article].value }, { it[Tags.name] })

        val countExpr = Favorites.user.count()
        val counts = Favorites.slice(Favorites.article, countExpr)
            .select { Favorites.article inList ids }
            .groupBy(Favorites.article)
            .associate { it[Favorites.article].value to it[countExpr] }

        val favoritedByViewer: Set<Long> = if (viewerId == null) emptySet() else
            Favorites.select { (Favorites.article inList ids) and (Favorites.user eq viewerId) }
                .map { it[Favorites.article].value }
                .toSet()

        ids.mapNotNull { id ->
            rows[id]?.let { row ->
                Article(
                    slug = row[Articles.slug],
                    title = row[Articles.title],
                    description = row[Articles.description],
                    body = row[Articles.body],
                    tagList = tagsByArticle[id].orEmpty().sorted(),
                    createdAt = Date(row[Articles.createdAt]),
                    updatedAt = Date(row[Articles.updatedAt]),
                    favorited = id in favoritedByViewer,
                    favoritesCount = counts[id] ?: 0,
                    // following stays false: the follow endpoints are still stubs in this codebase.
                    author = Profile(row[Users.username], row[Users.bio], row[Users.image], following = false)
                )
            }
        }
    }
}
