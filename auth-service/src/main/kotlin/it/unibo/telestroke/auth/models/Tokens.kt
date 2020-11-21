package it.unibo.telestroke.auth.models

/**
 * The tokens.
 * @param token The JWT token.
 * @param refreshToken The refresh token.
 */
data class Tokens(val token: String,
                  val refreshToken: String)
