package it.unibo.telestroke.peer.models.request

data class AddPeerRequest(val peerId: String,
                          val userId: String,
                          val description: String)
