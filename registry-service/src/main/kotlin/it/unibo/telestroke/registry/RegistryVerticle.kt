package it.unibo.telestroke.registry

import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.servicediscovery.getRecordsAwait
import io.vertx.kotlin.servicediscovery.unpublishAwait
import it.unibo.telestroke.common.BaseVerticle
import it.unibo.telestroke.common.extensions.asyncHandler
import it.unibo.telestroke.common.extensions.error
import it.unibo.telestroke.common.extensions.json
import it.unibo.telestroke.common.extensions.success
import it.unibo.telestroke.common.utils.Scheduler
import kotlinx.coroutines.runBlocking

/**
 * Registry verticle.
 */
class RegistryVerticle : BaseVerticle() {

  companion object {
    private val log: Logger = LoggerFactory.getLogger(RegistryVerticle::class.java)

    private const val DefaultHealthCheckTimeout = 2000L
    private const val DefaultHealthCheckSchedulingTime = 30000L
    private const val DefaultHealthCheckFailureBeforeRemoval = 2
  }

  //region fields
  private lateinit var webClient: WebClient
  //endregion

  override suspend fun start() {
    super.start()
    scheduleHealthCheck()
  }

  override suspend fun stop() {
    webClient.close()
    Scheduler.dispose()
    super.stop()
  }

  override suspend fun initialize() {
    super.initialize()

    //initialize the webclient
    webClient = WebClient.wrap(vertx.createHttpClient())
  }

  override fun createRouter(baseUrl: String): Router {
    return getBaseRouter().apply {
      get("/${baseUrl}/services").asyncHandler { handleGetServices(it) }
    }
  }

  //region handlers
  /**
   * Handles the retrieval of all registered services.
   */
  private suspend fun handleGetServices(context: RoutingContext) {
    log.debug("Requested registered services...")

    try {
      val records = discovery.getRecordsAwait(json { obj() })
      context.response().json(records.map{ JsonObject.mapFrom(it)})
      log.debug("Successfully sent registered services")
    } catch (e: Throwable) {
      log.error("Unable to retrieve services", e)
      context.response().error()
    }
  }
  //endregion

  //region helpers
  /**
   * Schedules health check for all instances.
   */
  private fun scheduleHealthCheck() {
    val schedulingTime = getConfig<Long>("/healthCheck/schedulingTime") ?: DefaultHealthCheckSchedulingTime
    val healthCheckTimeout = getConfig<Long>("/healthCheck/timeout") ?: DefaultHealthCheckTimeout

    val instancesStatus = mutableMapOf<String, Int>()

    Scheduler.start(schedulingTime) {
      try {
        runBlocking {
          log.debug("Checking instances health status...")

          val records = discovery.getRecordsAwait(json { obj() })

          //clear orphan instances
          val recordsId = records.map { it.registration }
          instancesStatus
            .filter { !recordsId.contains(it.key) }
            .forEach { instancesStatus.remove(it.key) }

          //evict instances that are no longer active
          val active = records.map {
            val port = it.location.getInteger("port")
            val host = it.location.getString("host")
            try {
              val response = webClient.get(port, host, DefaultHeartbeatRoute).timeout(healthCheckTimeout).sendAwait()
              val recordId = response.bodyAsJsonObject().getString("registration")
              Pair(recordId, response.success())
            } catch (e: Throwable) {
              log.trace("Error occurred while trying to contact instance ${it.registration}: ${e.message}")
              Pair(it.registration, false)
            }
          }.toMap()

          //perform health check
          records.filter { //check health
            val status = active[it.registration] ?: false
            log.trace("Instance ${it.registration} health check status: ${if (status) "OK" else "FAILED"}")
            !status
          }
          .map { //increment failures counter
            instancesStatus[it.registration] = instancesStatus[it.registration]?.inc() ?: 1
            log.trace("Instance ${it.registration} of service ${it.name} failed to reply to heartbeat (failures: ${instancesStatus[it.registration]})")
            it
          }
          .filter { //filter all records that have reached the maximum failures
            instancesStatus[it.registration]!! >= DefaultHealthCheckFailureBeforeRemoval
          }
          .forEach { //unpublish all
            log.trace("Removing record ${it.registration} for service ${it.name} due to failed health check")
            discovery.unpublishAwait(it.registration)
            instancesStatus.remove(it.registration)
          }
        }
      } catch (e: Throwable) {
        log.error("Error occurred while health checking instances", e)
      }
    }
  }
  //endregion
}
