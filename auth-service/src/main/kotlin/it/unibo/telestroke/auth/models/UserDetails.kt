package it.unibo.telestroke.auth.models

/**
 * The user details passed if the login succeeded.
 * @param id The identifier
 * @param username The username
 * @param firstName The first name
 * @param lastName The last name
 * @param email The email
 */
data class UserDetails(val id: String,
                       val username: String,
                       val firstName: String,
                       val lastName: String,
                       val email: String)
