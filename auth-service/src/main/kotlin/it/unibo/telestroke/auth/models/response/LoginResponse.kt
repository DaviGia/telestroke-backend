package it.unibo.telestroke.auth.models.response

import it.unibo.telestroke.auth.models.UserDetails

/**
 * The login response.
 * @param user The user details
 * @param token The JWT token
 * @param refreshToken The JWT refresh token
 */
data class LoginResponse(val user: UserDetails,
                         val token: String,
                         val refreshToken: String)
