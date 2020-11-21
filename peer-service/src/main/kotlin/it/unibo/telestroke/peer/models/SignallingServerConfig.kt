package it.unibo.telestroke.peer.models

/**
 * Signalling server configuration.
 */
data class SignallingServerConfig(val host: String,
                                  val port: Int,
                                  val path: String,
                                  val apiKey: String,
                                  val secure: Boolean)
