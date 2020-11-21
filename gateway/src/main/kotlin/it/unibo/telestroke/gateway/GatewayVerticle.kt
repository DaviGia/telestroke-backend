package it.unibo.telestroke.gateway

import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.sendBufferAwait
import io.vertx.kotlin.redis.closeAwait
import io.vertx.kotlin.servicediscovery.getRecordAwait
import io.vertx.redis.RedisClient
import io.vertx.redis.RedisOptions
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.Status
import it.unibo.telestroke.common.BaseVerticle
import it.unibo.telestroke.common.extensions.asyncHandler
import it.unibo.telestroke.common.extensions.error
import it.unibo.telestroke.common.utils.HealthChecksBuilder
import it.unibo.telestroke.common.utils.HttpRequestUtils
import it.unibo.telestroke.common.utils.Scheduler
import it.unibo.telestroke.gateway.auth.RedisJwtAuthProvider
import it.unibo.telestroke.gateway.models.RoutesConfig

/**
 * Gateway verticle.
 */
class GatewayVerticle : BaseVerticle() {

  companion object {
    private val log: Logger = LoggerFactory.getLogger(GatewayVerticle::class.java)

    private const val SERVICE_ID_KEY = "param0"
    private const val REQUEST_URL_KEY = "param1"
  }

  //region fields
  /**
   * The routes configuration.
   */
  private lateinit var routes: RoutesConfig

  /**
   * The webclient
   */
  private lateinit var webClient: WebClient
  /**
   * The redisClient
   */
  private lateinit var redisClient: RedisClient
  //endregion

  override suspend fun start() {
    super.start()
    //initialize main components
    initialize()
  }

  override suspend fun stop() {
    Scheduler.dispose()
    redisClient.closeAwait()
    super.stop()
  }

  override suspend fun initialize() {
    super.initialize()

    routes = getConfig<RoutesConfig>("/gateway") ?: throw IllegalStateException("Missing gateway configuration")

    //configure redis
    val redisOptions = getConfig<RedisOptions>("/redis") ?: throw IllegalStateException("Redis configuration is missing")
    redisClient = RedisClient.create(vertx, redisOptions)

    //configure a single webClient for re-routing
    webClient = WebClient.wrap(vertx.createHttpClient())
  }

  override fun createRouter(baseUrl: String): Router {
    val healthChecks = HealthChecksBuilder().redis(redisClient).build(vertx)
    return getBaseRouter(healthChecks).apply {
      route().handler(getCorsHandler())
      route().handler(getAuthHandler())
      routeWithRegex("\\/${baseUrl}\\/([^\\\\/]+)(.*)").asyncHandler { handleRequest(it) }
    }
  }

  //region handlers
  /**
   * Handles the incoming requests and forwards the request to the correct service.
   * @param context The routing context
   */
  private suspend fun handleRequest(context: RoutingContext) {
    log.debug("Forwarding incoming request...")

    var client: WebClient? = null

    try {
      val serviceId = context.pathParam(SERVICE_ID_KEY)
      val requestPath = context.pathParam(REQUEST_URL_KEY)

      val service = routes.routes.find { it.id == serviceId } ?:
        throw UnsupportedOperationException("No route is configured for the service: $serviceId")

      val record = discovery.getRecordAwait(json {
        obj("name" to service.serviceName)
      }) ?: throw UnsupportedOperationException("No discovery record found for the service: $serviceId")

      if (record.status != Status.UP) {
        throw UnsupportedOperationException("Requested service is not up")
      }

      val reference = discovery.getReference(record)
      client = reference.getAs(WebClient::class.java)

      val originalRequest = context.request()
      val originalResponse = originalRequest.response()

      //build request to forward
      val requestUri = buildRequestUri(record, requestPath)
      val request = client.request(originalRequest.method(), requestUri)
      HttpRequestUtils.copyHeaders(originalRequest, request)
      HttpRequestUtils.copyQueryParams(context, request)

      //make request and receive response
      val response = if (context.body != null) request.sendBufferAwait(context.body) else request.sendAwait()
      HttpRequestUtils.copyHeaders(response, originalResponse)

      log.debug("Sending response from remote service")
      val responseBody = response.body()
      originalResponse.apply {
        statusCode = response.statusCode()
        if (responseBody != null) end(responseBody) else end()
      }
      log.debug("Successfully sent the response from remote service")

    } catch (e: Throwable) {
      log.error("Unable to forward request", e)
      context.response().error()
    } finally {
      client?.apply {
        ServiceDiscovery.releaseServiceObject(discovery, client)
      }
    }
  }
  //endregion

  //region helpers
  /**
   * Gets the auth handler.
   * @return The JWT auth handler
   */
  private fun getAuthHandler(): Handler<RoutingContext> {
    try {
      val ignoreRoutes = getConfig<List<String>>("/auth/ignoreRoutes") ?: listOf()

      val redisProvider = RedisJwtAuthProvider(redisClient, JWTAuthOptions() /*TODO: always default for now*/)
      val jwtHandler = JWTAuthHandler.create(redisProvider)

      return Handler { event ->
        if (ignoreRoutes.none { event.request().path().startsWith(it, true) }) {
          if (event.user() != null) {
            event.next()
          } else {
            jwtHandler.handle(event)
          }
        } else {
          event.next()
        }
      }
    } catch (e: Throwable) {
      log.error("Unable to configure authorization handler", e)
      throw e
    }
  }

  /**
   * Builds the cors handler.
   * @return The cors handler
   */
  private fun getCorsHandler(): CorsHandler {

    val headers = setOf(
      "x-forwarded-*",
      "x-requested-with",
      "origin",
      "content-type",
      "accept",
      "authorization",
      "X-PINGARUNER"
    )

    val methods = setOf(
      HttpMethod.GET,
      HttpMethod.POST,
      HttpMethod.OPTIONS,
      HttpMethod.DELETE,
      HttpMethod.PATCH,
      HttpMethod.PUT
    )

    // TODO: for security reasons .* should be replaced with specific url
    return CorsHandler.create(".*")
      .allowedHeaders(headers)
      .allowedMethods(methods)
      .allowCredentials(true)
  }

  /**
   * Builds the request uri from the service discovery record and received request url.
   * @param record The record
   * @param requestedUrl The requested url
   * @return The absolute url
   */
  private fun buildRequestUri(record: Record, requestedUrl: String): String {
    val root = record.location.getString("root")
    return "${root.trimEnd('/')}$requestedUrl"
  }
  //endregion
}
