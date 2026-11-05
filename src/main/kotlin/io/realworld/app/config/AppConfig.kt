package io.realworld.app.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.log
import io.ktor.application.install
import io.ktor.auth.Authentication
import io.ktor.auth.jwt.jwt
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.routing.Routing
import io.ktor.routing.route
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.realworld.app.domain.exceptions.NotFoundException
import io.realworld.app.domain.exceptions.UnauthorizedException
import io.realworld.app.utils.JwtProvider
import io.realworld.app.web.ErrorResponse
import io.realworld.app.web.articles
import io.realworld.app.web.controllers.ArticleController
import io.realworld.app.web.controllers.CommentController
import io.realworld.app.web.controllers.ProfileController
import io.realworld.app.web.controllers.TagController
import io.realworld.app.web.controllers.UserController
import io.realworld.app.web.profiles
import io.realworld.app.web.tags
import io.realworld.app.web.users
import org.kodein.di.generic.instance

const val SERVER_PORT = 8080

fun setup(isCio: Boolean = true): ApplicationEngine {
    DbConfig.setup("jdbc:h2:mem:DATABASE_TO_UPPER=false;", "sa", "")
    // Typed explicitly: the inferred common supertype is Ktor's engine-internal BaseApplicationEngine.
    val engine: ApplicationEngineFactory<ApplicationEngine, out ApplicationEngine.Configuration> =
        if (isCio) CIO else Netty
    return server(engine)
}

fun server(
    engine: ApplicationEngineFactory<ApplicationEngine,
        out ApplicationEngine.Configuration>
): ApplicationEngine {
    return embeddedServer(
        engine,
        port = SERVER_PORT,
        watchPaths = listOf("mainModule"),
        module = Application::mainModule
    )
}

fun Application.mainModule() {
    val userController by ModulesConfig.kodein.instance<UserController>()
    val profileController by ModulesConfig.kodein.instance<ProfileController>()
    val articleController by ModulesConfig.kodein.instance<ArticleController>()
    val commentController by ModulesConfig.kodein.instance<CommentController>()
    val tagController by ModulesConfig.kodein.instance<TagController>()

    install(CallLogging)
    install(ContentNegotiation) {
        jackson {
            // RealWorld clients expect ISO-8601 strings, not epoch millis.
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    install(Authentication) {
        jwt {
            verifier(JwtProvider.verifier)
            authSchemes("Token")
            validate { credential ->
                if (credential.payload.audience.contains(JwtProvider.audience)) {
                    userController.getUserByEmail(credential.payload.claims["email"]?.asString())
                } else null
            }
        }
    }
    install(StatusPages) {
        // RealWorld error format: {"errors":{"body":["..."]}}. The previous handler returned
        // the pipeline object's toString() with a 500 for every failure, including bad input.
        exception<IllegalArgumentException> { cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(mapOf("body" to listOf(cause.message))))
        }
        exception<JsonProcessingException> {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(mapOf("body" to listOf("Request body is malformed or missing required fields.")))
            )
        }
        exception<NotFoundException> { cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(mapOf("body" to listOf(cause.message))))
        }
        exception<UnauthorizedException> { cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(mapOf("body" to listOf(cause.message))))
        }
        exception<Exception> { cause ->
            call.application.log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(mapOf("body" to listOf("Internal server error"))))
        }
    }

    install(Routing) {
        // The RealWorld spec, the bundled Postman suite and the repo's own tests all use /api.
        route("api") {
            users(userController)
            profiles(profileController)
            articles(articleController, commentController)
            tags(tagController)
        }
    }
}
