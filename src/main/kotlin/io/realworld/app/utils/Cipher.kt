package io.realworld.app.utils

import com.auth0.jwt.algorithms.Algorithm
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * The JWT signing key. It used to be a string literal in this file, so anyone with the source
 * could mint a valid token for any user. It now comes from the JWT_SECRET environment variable.
 * Without it, a random key is generated at startup. That loses nothing here: the database is
 * in-memory, so users and their tokens are gone after a restart anyway. Set JWT_SECRET when
 * tokens must survive restarts or be shared between instances.
 */
object Cipher {
    private const val MIN_SECRET_LENGTH = 32

    val algorithm: Algorithm = Algorithm.HMAC256(loadSecret())

    private fun loadSecret(): String {
        val configured = System.getenv("JWT_SECRET")
        if (configured != null) {
            require(configured.length >= MIN_SECRET_LENGTH) {
                "JWT_SECRET must be at least $MIN_SECRET_LENGTH characters."
            }
            return configured
        }
        LoggerFactory.getLogger(Cipher::class.java)
            .warn("JWT_SECRET is not set; using a random signing key. Tokens will not survive a restart.")
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
