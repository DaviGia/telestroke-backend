package it.unibo.telestroke.auth.models.requests

/**
 * The logout request.
 * @param refreshToken The refresh token.
 */
data class LogoutRequest(val refreshToken: String)
