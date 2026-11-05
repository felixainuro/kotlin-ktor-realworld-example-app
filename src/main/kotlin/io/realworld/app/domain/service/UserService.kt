package io.realworld.app.domain.service

import io.realworld.app.domain.Profile
import io.realworld.app.domain.User
import io.realworld.app.domain.exceptions.NotFoundException
import io.realworld.app.domain.exceptions.UnauthorizedException
import io.realworld.app.domain.repository.UserRepository
import io.realworld.app.utils.Cipher
import io.realworld.app.utils.JwtProvider
import java.util.Base64

class UserService(private val jwtProvider: JwtProvider, private val userRepository: UserRepository) {
    private val base64Encoder = Base64.getEncoder()

    fun create(user: User): User {
        userRepository.findByEmail(user.email).apply {
            require(this == null) { "Email already registered!" }
        }
        // Without this the unique index rejects the insert and the caller gets a 500.
        require(userRepository.findByUsername(user.username!!) == null) { "Username already taken!" }
        userRepository.create(user.copy(password = hashPassword(user.password!!)))
        return user.copy(token = generateJwtToken(user))
    }

    fun authenticate(user: User): User {
        val userFound = userRepository.findByEmail(user.email)
        if (userFound?.password == hashPassword(user.password!!)) {
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
        // A new password must be stored hashed like at registration, or the user can never log in
        // again. A blank password means "unchanged". The response carries a fresh token because
        // the old one names the old email.
        val toSave = user.copy(password = user.password?.takeIf { it.isNotBlank() }?.let(::hashPassword))
        return userRepository.update(email, toSave)?.let { it.copy(token = generateJwtToken(it)) }
    }

    private fun hashPassword(password: String): String = String(base64Encoder.encode(Cipher.encrypt(password)))

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
