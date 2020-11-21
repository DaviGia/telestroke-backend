package it.unibo.telestroke.common.models

import com.fasterxml.jackson.annotation.JsonAlias

/**
 * The user information
 * @param id The identifier
 * @param firstName The first name
 * @param lastName The last name
 * @param email The email
 */
data class UserInfo(@JsonAlias("_id") val id: String,
                    val firstName: String,
                    val lastName: String,
                    val email: String)
