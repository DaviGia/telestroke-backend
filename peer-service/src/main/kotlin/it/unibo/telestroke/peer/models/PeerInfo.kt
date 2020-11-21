package it.unibo.telestroke.peer.models

import it.unibo.telestroke.common.models.UserInfo

data class PeerInfo(val id: String,
                    val user: UserInfo,
                    val description: String,
                    val currentSession: BaseSessionInfo? = null)
