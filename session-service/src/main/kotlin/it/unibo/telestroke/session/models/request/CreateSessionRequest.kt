package it.unibo.telestroke.session.models.request

data class CreateSessionRequest(val specialist: String,
                                val operator: String,
                                val peerId: String,
                                val template: String)
