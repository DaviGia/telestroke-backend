package it.unibo.telestroke.auth.models.requests

import it.unibo.telestroke.auth.models.RegistrationDetails

/**
 * The account details.
 * @param username The username
 * @param password The password
 * @param details The user additional parameters
 */
data class RegistrationRequest(val username: String,
                               val password: String,
                               val details: RegistrationDetails)
