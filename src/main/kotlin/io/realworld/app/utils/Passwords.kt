package io.realworld.app.utils

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies

/**
 * Salted, deliberately slow password hashing with bcrypt. Replaces an unsalted HMAC keyed with
 * the (then public) JWT secret, which made every stored password cheap to brute-force.
 * Passwords over bcrypt's 72-byte limit are pre-hashed with SHA-512 instead of being rejected.
 */
object Passwords {
    private const val COST = 10
    private val version = BCrypt.Version.VERSION_2A
    private val longPasswords = LongPasswordStrategies.hashSha512(version)
    private val hasher = BCrypt.with(version, longPasswords)
    private val verifier = BCrypt.verifyer(version, longPasswords)

    fun hash(password: String): String = hasher.hashToString(COST, password.toCharArray())

    fun matches(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified
}
