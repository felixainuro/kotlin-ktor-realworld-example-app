package io.realworld.app.web.controllers

import io.ktor.application.ApplicationCall
import io.ktor.auth.authentication
import io.ktor.request.receive
import io.ktor.response.respond
import io.realworld.app.domain.User
import io.realworld.app.domain.UserDTO
import io.realworld.app.domain.service.UserService

class UserController(private val userService: UserService) {
    suspend fun login(ctx: ApplicationCall) {
        ctx.receive<UserDTO>().apply {
            userService.authenticate(this.validLogin()).apply {
                ctx.respond(UserDTO(this.withoutPassword()))
            }
        }
    }

    suspend fun register(ctx: ApplicationCall) {
        ctx.receive<UserDTO>().apply {
            userService.create(this.validRegister()).apply {
                ctx.respond(UserDTO(this.withoutPassword()))
            }
        }
    }

    fun getUserByEmail(email: String?): User {
        return email.let {
            require(!it.isNullOrBlank()) { "User not logged or with invalid email." }
            userService.getByEmail(it)
        }
    }

    suspend fun getCurrent(ctx: ApplicationCall) {
        ctx.respond(UserDTO(ctx.authentication.principal<User>()?.withoutPassword()))
    }

    suspend fun update(ctx: ApplicationCall) {
        val email = ctx.authentication.principal<User>()?.email
        require(!email.isNullOrBlank()) { "User not logged." }
        ctx.receive<UserDTO>().also { userDto ->
            userService.update(email, userDto.validToUpdate()).apply {
                ctx.respond(UserDTO(this?.withoutPassword()))
            }
        }
    }

    // User responses never carry the password: register used to echo it in plain text and
    // login / current user returned the stored hash.
    private fun User.withoutPassword() = copy(password = null)
}
