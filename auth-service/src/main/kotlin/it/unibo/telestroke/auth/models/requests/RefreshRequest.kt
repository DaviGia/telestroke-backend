package it.unibo.telestroke.auth.models.requests

/**
 * The refresh request.
 * @param token The JWT token.
 * @param refreshToken The refresh token.
 */
data class RefreshRequest(val token: String,
                          val refreshToken: String)
