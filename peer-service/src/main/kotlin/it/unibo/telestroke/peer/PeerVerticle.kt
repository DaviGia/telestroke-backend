package it.unibo.telestroke.peer

import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.http.HttpClient
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import io.vertx.kotlin.ext.mongo.*
import io.vertx.kotlin.ext.web.client.sendAwait
import it.unibo.telestroke.common.models.Peer
import it.unibo.telestroke.common.mongo.MongoVerticle
import it.unibo.telestroke.common.mongo.models.PageResponse
import it.unibo.telestroke.common.utils.Scheduler
import it.unibo.telestroke.peer.models.PeerInfo
import it.unibo.telestroke.peer.models.SignallingServerConfig
import it.unibo.telestroke.peer.models.request.AddPeerRequest
import it.unibo.telestroke.common.extensions.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Peer verticle.
 */
class PeerVerticle : MongoVerticle() {

  companion object {
    private val log: Logger = LoggerFactory.getLogger(PeerVerticle::class.java)

    private const val ID_PARAM_KEY = "id"
    private const val PEERJS_API_BASE_URL = "api"
    private const val PEERJS_PEERS_ROUTE_URL = "peers"

    private const val PAGINATION_API_ROUTE = "pages"

    private const val PEER_ID_PROPERTY = "id"

    private const val PEERS_FILTER_KEY = "filter"
    private const val PEERS_STATUS_FILTER_FREE = "free"

    private const val DefaultPeersFetchTime = 1000L

    private const val DefaultSignallingServerHealthCheckHandlerName = "peerjs-server"
    private const val DefaultSignallingServerHealthCheckTimeout = 20000L
  }

  //region fields
  private lateinit var peerServerConfig: SignallingServerConfig

  private lateinit var httpClient: HttpClient
  private lateinit var webClient: WebClient

  private val lock: Mutex = Mutex(false)
  //endregion

  override suspend fun start() {
    super.start()

    //schedule peers checking
    schedulePeersChecking()
  }

  override suspend fun stop() {
    Scheduler.stop()
    webClient.close()
    super.stop()
  }

  override suspend fun initialize() {
    super.initialize()

    //fetch peer configuration
    peerServerConfig = getConfig<SignallingServerConfig>("/peerjs/serverConfig") ?:
      throw IllegalStateException("Signalling server configuration is missing")

    val allowUntrusted = getConfig<Boolean>("/peerjs/allowUntrusted") ?: false

    //create web client
    val options = httpClientOptionsOf(defaultHost = peerServerConfig.host, defaultPort = peerServerConfig.port, ssl = peerServerConfig.secure, trustAll = allowUntrusted)
    httpClient = vertx.createHttpClient(options)
    webClient = WebClient.wrap(httpClient)
  }

  override fun createRouter(baseUrl: String): Router {
    val healthChecks = mongoHealthCheckBuilder()
      .custom(
        DefaultSignallingServerHealthCheckHandlerName,
        DefaultSignallingServerHealthCheckTimeout,
        signallingServerHealthCheck()
      )
      .build(vertx)

    return getBaseRouter(healthChecks).apply {
      get("/${baseUrl}/peers/info").asyncHandler { handleGetPeersInfo(it) }
      get("/${baseUrl}/peers/info/$PAGINATION_API_ROUTE").asyncHandler { handleGetPeersInfoPage(it) }
      get("/${baseUrl}/peers/info/:$ID_PARAM_KEY").asyncHandler { handleGetPeerInfo(it) }
      get("/${baseUrl}/peers").asyncHandler { handleGetPeers(it) }
      get("/${baseUrl}/peers/$PAGINATION_API_ROUTE").asyncHandler { handleGetPeersPage(it) }
      get("/${baseUrl}/peers/:$ID_PARAM_KEY").asyncHandler { handleGetPeer(it) }
      post("/${baseUrl}/peers").asyncHandler { handleAddPeer(it) }
    }
  }

  //region handlers

  //get a peer
  private suspend fun handleGetPeer(context: RoutingContext) {
    log.debug("Requested a peer...")

    try {
      val peerId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }
      val result = mongoClient.findOneAwait(PEERS_COLLECTION, json { obj(DEFAULT_MONGO_ID_KEY to peerId) }, null)
      result?.let {
        context.response().json(JsonObject.mapFrom(it.flattenId().mapTo(Peer::class.java)))
        log.debug("Successfully sent peer")
      } ?: run {
        context.response().notFound()
        log.debug("Unable to found requested peer")
      }
    } catch (e: Throwable) {
      log.error("Unable to retrieve peers", e)
      context.response().error("Server error")
    }
  }

  //get the peers
  private suspend fun handleGetPeers(context: RoutingContext) {
    log.debug("Requested peers...")

    try {
      context.response().json(json { array(getPeers()) })
      log.debug("Successfully sent peers")
    } catch (e: Throwable) {
      log.error("Unable to retrieve peers", e)
      context.response().error("Server error")
    }
  }

  //retrieves a peers page
  private suspend fun handleGetPeersPage(context: RoutingContext) {
    log.debug("Requested peers page...")
    sendPageResults<Peer>(context, PEERS_COLLECTION)
  }

  //get peer info
  private suspend fun handleGetPeerInfo(context: RoutingContext) {
    log.debug("Requested peer info...")

    try {
      val peerId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      val options = aggregateOptionsOf(batchSize = 10000, maxAwaitTime = 0, allowDiskUse = true, maxTime = 0)
      val pipeline = json {
        array(
          obj("\$match" to obj(DEFAULT_MONGO_ID_KEY to peerId)),
          *getPeerInfoPipeline()
        )
      }

      val cursor = mongoClient.aggregateWithOptions(PEERS_COLLECTION, pipeline, options)
      val receiver = cursor.fetch(1).toChannel(vertx)
      val result = receiver.receive()

      context.response().json(JsonObject.mapFrom(result.flattenId().mapTo(PeerInfo::class.java)))
      log.debug("Successfully sent peer info")
    } catch (e: ClosedReceiveChannelException) {
      context.response().notFound("Peer not found")
    } catch (e: Throwable) {
      log.error("Unable to retrieve peer info", e)
      context.response().error("Server error")
    }
  }

  //get peers info (?status)
  private suspend fun handleGetPeersInfo(context: RoutingContext) {
    log.debug("Requested peers info...")

    try {
      val filter = context.queryParam(PEERS_FILTER_KEY)
      val options = aggregateOptionsOf(batchSize = 10000, maxAwaitTime = 0, allowDiskUse = true, maxTime = 0)
      val pipeline = when (filter.firstOrNull()) {
        PEERS_STATUS_FILTER_FREE -> json {
          array(
            json { obj("\$match" to obj("currentSession" to null)) },
            *getPeerInfoPipeline()
          )
        }
        else -> json { array(*getPeerInfoPipeline()) }
      }

      val cursor = mongoClient.aggregateWithOptions(PEERS_COLLECTION, pipeline, options)
      val receiver = cursor.toChannel(vertx)
      val results = receiver.toList().map{ it.flattenId().mapTo(PeerInfo::class.java) }

      context.response().json(results.map { JsonObject.mapFrom(it) })
      log.debug("Successfully sent peers info")
    } catch (e: Throwable) {
      log.error("Unable to retrieve peers info", e)
      context.response().error()
    }
  }

  //get peers info page
  private suspend fun handleGetPeersInfoPage(context: RoutingContext) {
    log.debug("Requested peers info page...")

    try {
      //parameters
      val pageParam = context.queryParam(PAGE_QUERY_KEY)
      val limitParam = context.queryParam(LIMIT_QUERY_KEY)
      val page = if (pageParam.isNotEmpty()) pageParam[0].toInt() else 0
      val limit = if (limitParam.isNotEmpty()) limitParam[0].toInt() else 20
      if (page < 0 || limit <= 0) { context.response().badRequest(); return; }

      //check for out of bound
      val count = mongoClient.countAwait(PEERS_COLLECTION, json { obj() })
      if (page * limit > count) { context.response().badRequest(); return; }

      log.debug("Resolving results for page $page (limit: $limit, count: $count)")

      val options = aggregateOptionsOf(batchSize = 10000, maxAwaitTime = 0, allowDiskUse = true, maxTime = 0)
      val pipeline = json {
        array(
          obj("\$skip" to page * limit),
          obj("\$limit" to limit),
          *getPeerInfoPipeline()
        )
      }

      val cursor = mongoClient.aggregateWithOptions(PEERS_COLLECTION, pipeline, options)
      val receiver = cursor.toChannel(vertx)
      val results = receiver.toList().map { it.flattenId().mapTo(PeerInfo::class.java) }

      val response = PageResponse<PeerInfo>(page, limit, count, results)
      context.response().json(JsonObject.mapFrom(response))
      log.debug("Successfully sent peers info page")
    } catch (e: Throwable) {
      log.error("Unable to retrieve peers info page", e)
      context.response().error()
    }
  }

  //add a new peer
  private suspend fun handleAddPeer(context: RoutingContext) {
    log.debug("Received add peer request...")

    try {
      val request = context.bodyAsJson.mapTo(AddPeerRequest::class.java) ?: run { context.response().badRequest(); return }
      val peer = Peer(request.peerId, request.userId, request.description)

      if (!documentExists(USERS_COLLECTION, request.userId)) {
        log.error("Unable to add peer because the user was not found")
        context.response().badRequest("Incorrect user id")
        return
      }

      //avoids the removal of the newly created peer
      lock.withLock {
        if (documentExists(PEERS_COLLECTION, request.peerId)) {
          log.error("Unable to add peer because the id is already taken")
          context.response().badRequest("Id already taken")
          return
        }

        val json = JsonObject.mapFrom(peer).purgeId(PEER_ID_PROPERTY).put(DEFAULT_MONGO_ID_KEY, request.peerId)
        mongoClient.insertAwait(PEERS_COLLECTION, json)
      }

      context.response().success()
      log.debug("Successfully added peer")

    } catch (e: Throwable) {
      log.error("Unable to retrieve peers", e)
      context.response().error()
    }
  }
  //endregion

  //region helpers
  /**
   * Schedules the checking of the peers (removes peers that are no more active).
   */
  private fun schedulePeersChecking() {

    val schedulingTime = getConfig<Long>("/peerjs/schedulingTime") ?: DefaultPeersFetchTime
    var previousState: MutableList<String>? = null

    Scheduler.start(schedulingTime) {
      runBlocking(vertx.dispatcher()) {
        log.debug("Checking peers status...")

        try {
          //avoids the removal of an active peer
          lock.withLock {
            //if the previous state is empty, fetch initial mongodb status
            if (previousState == null) {
              log.debug("Fetching initial mongodb status")
              val activePeers = getPeers()
              previousState = mutableListOf(* activePeers.map { it.id }.toTypedArray())
            }

            val baseApiPath = peerServerConfig.path.trim('/')
            val req = webClient.get("/$baseApiPath/$PEERJS_API_BASE_URL/$PEERJS_PEERS_ROUTE_URL")
            val res = req.sendAwait()

            if (!res.success()) throw IllegalStateException("Server returned an error (${res.statusCode()}: ${res.statusMessage()})")
            val peerIds = res.bodyAsJsonArray()?.filterIsInstance<String>()
              ?: throw IllegalStateException("Response from peerjs server is malformed")

            //check if previous state is inconsistent
            if (peerIds.size != previousState!!.size || peerIds.any { !previousState!!.contains(it) }) {
              val query = json { obj(DEFAULT_MONGO_ID_KEY to obj("\$nin" to array(*peerIds.toTypedArray()))) }
              mongoClient.removeDocumentsAwait(PEERS_COLLECTION, query)?.let {
                //update current status
                previousState!!.clear()
                previousState!!.addAll(peerIds)
                log.debug("Updated mongodb list of peers (removed count: ${it.removedCount})")
              } ?: throw IllegalStateException("Remove query execution failed")
            } else {
              log.debug("The previous list of active peers is still consistent")
            }
          }
        } catch (e: Throwable) {
          log.error("Unable to check active peers status", e)
        }
      }
    }
  }

  /**
   * Retrieves peers from mongodb.
   */
  private suspend fun getPeers(): List<Peer> = resolveDocuments(PEERS_COLLECTION)

  /**
   * Gets the signalling server health check handler.
   */
  private fun signallingServerHealthCheck(): Handler<Promise<Status>> = Handler { promise ->
    val baseApiPath = peerServerConfig.path.trim('/')
    webClient.get("/$baseApiPath/$PEERJS_API_BASE_URL/$PEERJS_PEERS_ROUTE_URL").send {
      if (it.succeeded() && it.result().hasContent()) {
        promise.complete(Status.OK())
      } else {
        promise.complete(Status.KO())
      }
    }
  }
  //endregion

  //region mongo helper
  /**
   * Gets the peer info pipeline.
   */
  private fun getPeerInfoPipeline(): Array<JsonObject> {
    val array = json {
      array(
        obj(
          "\$lookup" to obj(
            "from" to USERS_DETAILS_COLLECTION,
            "localField" to "userId",
            "foreignField" to DEFAULT_MONGO_ID_KEY,
            "as" to "user"
          )
        ),
        obj("\$unwind" to "\$user"),
        obj(
          "\$lookup" to obj(
            "from" to SESSIONS_COLLECTION,
            "localField" to "currentSession",
            "foreignField" to DEFAULT_MONGO_ID_KEY,
            "as" to "currentSession"
          )
        ),
        obj("\$unwind" to obj("path" to "\$currentSession", "preserveNullAndEmptyArrays" to true)),
        obj(
          "\$lookup" to obj(
            "from" to USERS_DETAILS_COLLECTION,
            "localField" to "currentSession.specialist",
            "foreignField" to DEFAULT_MONGO_ID_KEY,
            "as" to "currentSession.specialist"
          )
        ),
        obj("\$unwind" to obj("path" to "\$currentSession.specialist", "preserveNullAndEmptyArrays" to true)),
        obj(
          "\$lookup" to obj(
            "from" to TEMPLATES_COLLECTION,
            "localField" to "currentSession.template",
            "foreignField" to DEFAULT_MONGO_ID_KEY,
            "as" to "currentSession.template"
          )
        ),
        obj("\$unwind" to obj("path" to "\$currentSession.template", "preserveNullAndEmptyArrays" to true)),
        obj("\$project" to obj(
          "userId" to 0,
          "currentSession.endDate" to 0,
          "currentSession.operator" to 0,
          "currentSession.phases" to 0,
          "currentSession.template.author" to 0,
          "currentSession.template.phases" to 0,
          "currentSession.template.createdAt" to 0,
          "currentSession.template.changedAt" to 0)
        ),
        obj("\$project" to obj(
          "_id" to 1,
          "user" to 1,
          "description" to 1,
          "currentSession" to obj("\$cond" to array(
                obj("\$eq" to array("\$currentSession", obj())),
                null,
                "\$currentSession"
              )
            )
          )
        )
      )
    }
    return array.filterIsInstance<JsonObject>().toTypedArray()
  }
  //endregion
}
