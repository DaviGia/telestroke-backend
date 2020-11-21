package it.unibo.telestroke.common.mongo.extensions

import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import it.unibo.telestroke.common.utils.HealthChecksBuilder

const val DefaultMongoPingCommand = "ping"
const val DefaultMongoHealthCheckHandlerName = "mongo"

/**
 * Adds a mongo health check.
 */
fun HealthChecksBuilder.mongo(mongoClient: MongoClient, name: String = DefaultMongoHealthCheckHandlerName, timeout: Long? = HealthChecksBuilder.DefaultTimeout) = apply {

  val handler = Handler<Promise<Status>> { promise ->
    mongoClient.runCommand(DefaultMongoPingCommand, json { obj(DefaultMongoPingCommand to 1) }) {
      if (it.succeeded() && !it.result().isEmpty) {
        promise.complete(Status.OK())
      } else {
        promise.complete(Status.KO())
      }
    }
  }

  timeout?.let { custom(name, it, handler) } ?: custom(name, handler)
}
