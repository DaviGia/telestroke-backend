package it.unibo.telestroke.auth.models

/**
 * The registration details.
 * @param firstName The first name
 * @param lastName The last name
 * @param email The email
 */
data class RegistrationDetails(val firstName: String,
                               val lastName: String,
                               val email: String)
