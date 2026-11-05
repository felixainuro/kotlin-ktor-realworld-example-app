package io.realworld.app.domain.service

import io.realworld.app.domain.Profile
import io.realworld.app.domain.User
import io.realworld.app.domain.exceptions.NotFoundException
import io.realworld.app.domain.exceptions.UnauthorizedException
import io.realworld.app.domain.repository.UserRepository
import io.realworld.app.utils.JwtProvider
import io.realworld.app.utils.Passwords

class UserService(private val jwtProvider: JwtProvider, private val userRepository: UserRepository) {
    fun create(user: User): User {
        userRepository.findByEmail(user.email).apply {
            require(this == null) { "Email already registered!" }
        }
        // Without this the unique index rejects the insert and the caller gets a 500.
        require(userRepository.findByUsername(user.username!!) == null) { "Username already taken!" }
        userRepository.create(user.copy(password = Passwords.hash(user.password!!)))
        return user.copy(token = generateJwtToken(user))
    }

    fun authenticate(user: User): User {
        val userFound = userRepository.findByEmail(user.email)
        if (userFound != null && Passwords.matches(user.password!!, userFound.password!!)) {
            return userFound.copy(token = generateJwtToken(userFound))
        }
        throw UnauthorizedException("email or password invalid!")
    }

    fun getByEmail(email: String): User {
        val user = userRepository.findByEmail(email)
        user ?: throw NotFoundException("User not found to get.")
        return user.copy(token = generateJwtToken(user))
    }

    fun getProfileByUsername(email: String, usernameFollowing: String): Profile {
        return userRepository.findByUsername(usernameFollowing).let { user ->
            user ?: throw NotFoundException("User not found to find.")
            Profile(user.username, user.bio, user.image, userRepository.findIsFollowUser(email, user.id!!))
        }
    }

    fun update(email: String, user: User): User? {
        // Someone else's email or username would hit a unique index and surface as a 500.
        if (user.email != email) {
            require(userRepository.findByEmail(user.email) == null) { "Email already registered!" }
        }
        user.username?.let { username ->
            val holder = userRepository.findByUsername(username)
            require(holder == null || holder.email == email) { "Username already taken!" }
        }
        // A new password must be stored hashed like at registration, or the user can never log in
        // again. A blank password means "unchanged". The response carries a fresh token because
        // the old one names the old email.
        val toSave = user.copy(password = user.password?.takeIf { it.isNotBlank() }?.let(Passwords::hash))
        return userRepository.update(email, toSave)?.let { it.copy(token = generateJwtToken(it)) }
    }

    private fun generateJwtToken(user: User): String? {
        return jwtProvider.createJWT(user)
    }

    fun follow(email: String, usernameToFollow: String): Profile {
        return userRepository.follow(email, usernameToFollow).let { user ->
            Profile(user.username, user.bio, user.image, true)
        }
    }

    fun unfollow(email: String, usernameToUnfollow: String): Profile {
        return userRepository.unfollow(email, usernameToUnfollow).let { user ->
            Profile(user.username, user.bio, user.image, false)
        }
    }
}
