package it.unibo.telestroke.common.models

import io.vertx.core.http.HttpServerOptions

/**
 * The http server configuration
 * @param host The host
 * @param port The port
 * @param root The root
 * @param options The server options
 */
data class ServerConfig(val host: String,
                        val port: Int,
                        val root: String = "/",
                        val options: HttpServerOptions = HttpServerOptions())
