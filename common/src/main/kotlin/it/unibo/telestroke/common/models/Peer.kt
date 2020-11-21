package it.unibo.telestroke.common.models

/**
 * The peer.
 * @param id The id
 * @param userId The user id
 * @param description The description
 * @param currentSession The session identifier bind to this peer
 */
data class Peer(val id: String,
                val userId: String,
                val description: String,
                val currentSession: String? = null)
