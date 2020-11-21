package it.unibo.telestroke.common

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.treeToValue
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.logging.LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME
import io.vertx.core.logging.SLF4JLogDelegateFactory
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.LoggerHandler
import io.vertx.kotlin.config.configRetrieverOptionsOf
import io.vertx.kotlin.config.configStoreOptionsOf
import io.vertx.kotlin.config.getConfigAwait
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.servicediscovery.publishAwait
import io.vertx.kotlin.servicediscovery.registerServiceImporterAwait
import io.vertx.kotlin.servicediscovery.unpublishAwait
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.kubernetes.KubernetesServiceImporter
import io.vertx.servicediscovery.types.HttpEndpoint
import it.unibo.telestroke.common.extensions.asyncHandler
import it.unibo.telestroke.common.extensions.error
import it.unibo.telestroke.common.extensions.json
import it.unibo.telestroke.common.models.ServerConfig
import it.unibo.telestroke.common.utils.ObjectMapperProvider.mapper

/**
 * Base verticle.
 */
open class BaseVerticle : CoroutineVerticle() {

  companion object {
    init {
      System.setProperty(LOGGER_DELEGATE_FACTORY_CLASS_NAME, SLF4JLogDelegateFactory::class.java.name)
      InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
      LoggerFactory.initialise()
    }

    private val log: Logger = LoggerFactory.getLogger(BaseVerticle::class.java)

    protected const val DefaultConfigFile = "service"

    const val DefaultHeartbeatRoute = "/heartbeat"
    const val DefaultHealthRoute = "/health*"

    const val DiscoveryHealthCheckHandlerName = "discovery"
    const val ActiveProfileEnvironmentVariable = "VERTX_PROFILE"
  }

  //region http
  /**
   * The underlying http server.
   */
  protected lateinit var httpServer: HttpServer
  //endregion

  //region discovery
  /**
   * The service discovery instance.
   */
  protected lateinit var discovery: ServiceDiscovery

  /**
   * The currently registered record for this instance.
   */
  private var instanceDiscoveryRecord: Record? = null
  //endregion

  //region config
  /**
   * The config retriever.
   */
  protected lateinit var  configRetriever: ConfigRetriever

  /**
   * The service configuration.
   */
  protected lateinit var serviceConfig: JsonObject

  /**
   * The cached representation
   */
  protected var cachedConfig: JsonNode? = null
  //endregion

  override suspend fun start() {
    log.debug("Initializing verticle...")

    try {

      //if not already registered, register kotlin module
      if (!DatabindCodec.mapper().registeredModuleIds.contains(KotlinModule().typeId)) {
        //globally register jackson kotlin module
        DatabindCodec.mapper().registerKotlinModule()
      }

      log.debug("Initializing configuration ...")

      //initializes the config retriever and the service config
      configRetriever = configureConfigRetriever()
      serviceConfig = configRetriever.getConfigAwait()

      log.debug("Initializing discovery ...")

      //initialize service discovery
      val discoveryOptions = getConfig<ServiceDiscoveryOptions>("/service") ?:
        throw IllegalStateException("Unable to config discovery, service configuration is missing")
      val backendConfig = getConfig("/discovery/backend") ?:
        throw IllegalStateException("Unable to config discovery, backend configuration is missing")

      discoveryOptions.backendConfiguration = backendConfig
      discovery = ServiceDiscovery.create(vertx, discoveryOptions)

      val onKubernetes = getConfig<Boolean>("/discovery/native/kubernetes", false) ?: false
      if (onKubernetes) {
        log.debug("Enabling service importer for kubernetes")
        discovery.registerServiceImporterAwait(KubernetesServiceImporter(), json { obj() })
      }

      log.debug("Initializing additional components...")

      //call initialize method of the super class
      initialize()

      log.debug("Starting http server...")

      //create http server
      httpServer = createHttpServer()

    } catch (e: Exception) {
      log.error("Error during verticle initialization", e)
      throw e
    }
  }

  override suspend fun stop() {
    configRetriever.close()
    //unpublish current record forcibly
    try {
      instanceDiscoveryRecord?.let { discovery.unpublishAwait(it.registration) }
    } catch (e: Throwable) {
      log.error("Unable to unpublish instance discovery record", e)
    } finally {
      discovery.close()
    }
  }

  //region protected
  /**
   * Retrieves the object from the configuration at the specified path.
   * @param path The path (format: '/my/path')
   * @param failOnUnknownProperties Whether to fail when unknown properties are detected or not.
   * @return The object
   */
  protected inline fun <reified T> getConfig(path: String, failOnUnknownProperties: Boolean = true): T? {
    cachedConfig = cachedConfig ?: mapper.readTree(serviceConfig.encode())

    val targetNode = cachedConfig?.at(path)
    return targetNode?.let {
      if (!it.isMissingNode) {
        mapper
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties)
          .treeToValue<T>(it)
      } else null
    }
  }

  /**
   * Retrieves the JsonObject from the configuration at the specified path.
   * @param path The path (format: '/my/path')
   * @return The JsonObject
   */
  protected fun getConfig(path: String): JsonObject? {
    cachedConfig = cachedConfig ?: mapper.readTree(serviceConfig.encode())

    val targetNode = cachedConfig?.at(path)?.apply {
      if (!this.isObject) {
        return null
      }
    }

    return targetNode?.let {
      JsonObject(mapper.writeValueAsString(it))
    }
  }

  /**
   * Creates the http server.
   * @return The http server.
   */
  protected open suspend fun createHttpServer(): HttpServer {

    //retrieving server config
    val config = getConfig<ServerConfig>("/server") ?:
      throw IllegalStateException("Unable to start http server because its configuration is missing")

    //create http server
    return vertx
      .createHttpServer(config.options)
      .requestHandler(createRouter(config.root.trim('/')))
      .listenAwait(config.port, config.host).apply {
        registerService(config.host, this.actualPort(), config.options.isSsl)
        log.info("Verticle http server listening at: ${config.host}:${this.actualPort()}")
      }
  }

  /**
   * Creates the router that will handle all requests to the underlying http server.
   * @param baseUrl The base url where the router is served.
   * @return The router.
   */
  protected open fun createRouter(baseUrl: String): Router = getBaseRouter()

  /**
   * Initialize all main components. Called before starting the underlying http server.
   */
  protected open suspend fun initialize() { }

  /**
   * Registers the current instance in the discovery backend.
   * @param host The host
   * @param port The port
   * @param ssl Whether the http server supports ssl or not.
   */
  protected suspend fun registerService(host: String, port: Int, ssl: Boolean = false) {
    try {
      val serviceConfig = getConfig<ServiceDiscoveryOptions>("/service") ?:
        throw IllegalStateException("Unable to config discovery, service configuration is missing")
      val serverConfig = getConfig<ServerConfig>("/server") ?:
        throw IllegalStateException("Unable to config discovery, server configuration is missing")

      //if the service is running on docker use the hostname env var instead of the provided host
      val onDocker = getConfig<Boolean>("/discovery/native/docker", false) ?: false
      var hostname = System.getenv("HOSTNAME") ?: String()
      if (onDocker && hostname.isNotEmpty()) {
        log.debug("Retrieved container hostname: $hostname")
      } else if (onDocker) {
        throw IllegalStateException("Failed to retrieve container hostname")
      } else {
        hostname = host
      }

      //publish this instance
      val record = HttpEndpoint.createRecord(serviceConfig.name, ssl, hostname, port, serverConfig.root, json { obj() })
      val publishedRecord = discovery.publishAwait(record)

      instanceDiscoveryRecord = publishedRecord

      log.debug("Service record published: ${publishedRecord.toJson()}")
    } catch (e: Throwable) {
      log.error("Unable to register server for discovery", e)
      throw e
    }
  }

  /**
   * Gets the base router.
   * @param healthChecks Additional health checks.
   * @return The router
   */
  protected fun getBaseRouter(healthChecks: HealthChecks? = null): Router {
    return Router.router(vertx).apply {
      route()
        .handler(BodyHandler.create())
        .handler(LoggerHandler.create())

      get(DefaultHealthRoute).handler(healthCheckHandler(healthChecks))
      route(DefaultHeartbeatRoute).asyncHandler { heartbeatHandler(it) }
    }
  }
  //endregion

  //region helpers
  /**
   * Initializes the config retriever.
   * @return The config retriever
   */
  private fun configureConfigRetriever(): ConfigRetriever {

    val activeProfile = System.getenv(ActiveProfileEnvironmentVariable)

    log.info("Using configuration profile: ${activeProfile ?: "default"}")

    val stores = mutableListOf<ConfigStoreOptions>()
    //required: service.yml
    stores.add(configStoreOptionsOf(json { obj("path" to "$DefaultConfigFile.yml") }, type = "file", format = "yaml", optional = false))
    //optional: service-${activeProfile}.yml
    activeProfile?.let {
      stores.add(configStoreOptionsOf(json { obj("path" to "$DefaultConfigFile-$activeProfile.yml") }, type = "file", format = "yaml", optional = true))
    }
    //optional: ${workingDir}/config/*.yml
    stores.add(configStoreOptionsOf(json {
      obj(
        "path" to "config",
        "filesets" to array(
          obj(
          "pattern" to "*.yml",
          "format" to "yaml"
          )
        )
      )
    }, type = "directory", format = "yaml", optional = true))

    //optional: kubernetes configmap
    stores.add(configStoreOptionsOf(json { obj("name" to "service-configmap")}, type = "configmap", format = "yaml", optional = true))
    //optional: kubernetes secrets
    stores.add(configStoreOptionsOf(json { obj("name" to "service-secrets", "secret" to true)}, type = "configmap", format = "yaml", optional = true))

    val configRetrieverOptions = configRetrieverOptionsOf(includeDefaultStores = true, stores = stores)
    return ConfigRetriever.create(vertx, configRetrieverOptions)
  }

  /**
   * The heartbeat handler.
   * @param context The routing context
   */
  private fun heartbeatHandler(context: RoutingContext) {
    instanceDiscoveryRecord?.let {
      val record = it.toJson().apply {
        remove("location")
      }
      context.response().json(record)
    } ?: run {
      log.error("Unable to correctly reply to health check, no instance discovery record is available")
      context.response().error()
    }
  }

  /**
   * The health check handler.
   * @param healthChecks The additional health check
   * @return The health check handler.
   */
  private fun healthCheckHandler(healthChecks: HealthChecks? = null): HealthCheckHandler {
    val handler = healthChecks?.let { HealthCheckHandler.createWithHealthChecks(healthChecks) } ?: HealthCheckHandler.create(vertx)
    if (getConfig<Boolean>("/discovery/healthCheck", false) != false) {
      registerDiscoveryHealthCheck(handler)
    }
    return handler
  }

  /**
   * The health check handler builder.
   * @return The health check handler.
   */
  private fun registerDiscoveryHealthCheck(handler: HealthCheckHandler) {
    handler.register(DiscoveryHealthCheckHandlerName, 5000L) { promise ->
        instanceDiscoveryRecord?.let { instance ->
          discovery.getRecord({ record -> record.registration == instance.registration }) {
            val result = it.result()
            if (it.succeeded() && result != null && result.registration == instance.registration) {
              promise.complete(Status.OK())
            } else {
              promise.complete(Status.KO())
            }
          }
        } ?: promise.complete(Status.KO())
      }
  }
  //endregion
}
