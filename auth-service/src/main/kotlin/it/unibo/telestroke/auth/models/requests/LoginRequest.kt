package it.unibo.telestroke.auth.models.requests

/**
 * The login request.
 * @param username The username.
 * @param password The password.
 */
data class LoginRequest(val username: String,
                        val password: String)
