package it.unibo.telestroke.common.utils

import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.ext.healthchecks.Status
import io.vertx.redis.RedisClient

/**
 * Health checks builder.
 */
class HealthChecksBuilder {

  companion object {
    const val DefaultRedisHealthCheckHandlerName = "redis"
    const val DefaultRedisReplyValue = "PONG"
    const val DefaultTimeout = 5000L
  }

  private val items = mutableListOf<HealthCheckItem>()

  /**
   * Adds a redis health check.
   */
  fun redis(redisClient: RedisClient, name: String = DefaultRedisHealthCheckHandlerName, timeout: Long? = DefaultTimeout) = apply {
    addItem(name, timeout, Handler { promise ->
      redisClient.ping {
        if (it.succeeded() && it.result() == DefaultRedisReplyValue) {
          promise.complete(Status.OK())
        } else {
          promise.complete(Status.KO())
        }
      }
    })
  }

  /**
   * Adds a custom health check.
   * @param name The name of the health check.
   * @param timeout The timeout (default no timeout)
   * @param handler The handler that will be invoked
   */
  fun custom(name: String, timeout: Long, handler: Handler<Promise<Status>>) = apply {
    addItem(name, timeout, handler)
  }

  /**
   * Adds a custom health check.
   * @param name The name of the health check.
   * @param handler The handler that will be invoked
   */
  fun custom(name: String, handler: Handler<Promise<Status>>) = apply {
    addItem(name, null, handler)
  }

  /**
   * Builds an instance of HealthChecks.
   * @return An instance of HealthChecks
   */
  fun build(vertx: Vertx): HealthChecks = HealthChecks.create(vertx).apply {
    items.forEach {
      it.timeout?.let {
          timeout -> register(it.name, timeout, it.handler)
      } ?: register(it.name, it.handler)
    }
  }

  /**
   * Adds a new item to the list of current health checks.
   * @param name The name of the health check.
   * @param timeout The timeout (default no timeout)
   * @param handler The handler that will be invoked
   */
  private fun addItem(name: String, timeout: Long? = null, handler: Handler<Promise<Status>>) {
    items.add(HealthCheckItem(name, handler, timeout))
  }

  private class HealthCheckItem(val name: String, val  handler: Handler<Promise<Status>>, val timeout: Long? = null)
}
