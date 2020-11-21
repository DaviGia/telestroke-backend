package it.unibo.telestroke.common.extensions

import io.vertx.ext.web.Route
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * An extension method for simplifying coroutines usage with Vert.x Web routers
 */
fun Route.asyncHandler(fn: suspend (RoutingContext) -> Unit) {
    handler { ctx ->
        GlobalScope.launch(ctx.vertx().dispatcher()) {
            try {
                fn(ctx)
            } catch (ex: HttpError) {
                println("Error detected inside async route handler: ${ex.message}")
                ex.printStackTrace(System.err)
                ctx.response().setStatusCode(ex.statusCode).setStatusMessage(ex.statusMessage).end()
            } catch (e: Throwable) {
                println("Error detected inside async route handler: ${e.message}")
                e.printStackTrace(System.err)
                ctx.fail(e)
            }
        }
    }
}
