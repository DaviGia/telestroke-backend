package it.unibo.telestroke.common.mongo

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.mongo.FindOptions
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.mongo.*
import it.unibo.telestroke.common.BaseVerticle
import it.unibo.telestroke.common.extensions.badRequest
import it.unibo.telestroke.common.extensions.error
import it.unibo.telestroke.common.extensions.flattenId
import it.unibo.telestroke.common.extensions.json
import it.unibo.telestroke.common.mongo.extensions.mongo
import it.unibo.telestroke.common.mongo.models.PageResponse
import it.unibo.telestroke.common.utils.HealthChecksBuilder

/**
 * Mongo verticle.
 */
open class MongoVerticle : BaseVerticle() {

  companion object {
    protected val log: Logger = LoggerFactory.getLogger(MongoVerticle::class.java)

    const val DEFAULT_MONGO_ID_KEY = "_id"

    const val PAGE_QUERY_KEY = "page"
    const val LIMIT_QUERY_KEY = "limit"

    const val PEERS_COLLECTION = "peers"
    const val SESSIONS_COLLECTION = "session"
    const val TEMPLATES_COLLECTION = "template"
    const val CHECKLISTS_COLLECTION = "checklist"
    const val USERS_COLLECTION = "user"
    const val USERS_DETAILS_COLLECTION = "user-details"
  }

  //region fields
  /**
   * The mongodb client that handles mongodb connection and operations.
   */
  protected lateinit var mongoClient: MongoClient
  //endregion

  override suspend fun stop() {
    mongoClient.close()
    super.stop()
  }

  override suspend fun initialize() {
    super.initialize()

    //initializes mongo client
    initMongoClient()
  }

  //region Helpers
  /**
   * Initializes the Mongo client.
   */
  private fun initMongoClient() {
    //configure mongodb client
    val mongoConfig = getConfig("/mongodb") ?:
    throw IllegalStateException("Mongodb configuration is missing")
    mongoClient = MongoClient.createShared(vertx, mongoConfig)
  }

  /**
   * Gets the health check builder initialized with a mongo health check.
   * @return The health check builder
   */
  protected fun mongoHealthCheckBuilder() = HealthChecksBuilder().mongo(mongoClient)
  //endregion

  //region mongodb helpers
  /**
   * Checks if a document exists with the specified id.
   * @param collection The collection name
   * @param id The document identifier
   * @return True, if the document exists; otherwise false.
   */
  protected suspend fun documentExists(collection: String, id: String) : Boolean {
    return if (id.isNotEmpty()) {
      val query = json {
        obj(DEFAULT_MONGO_ID_KEY to id)
      }
      documentExists(collection, query)
    } else {
      false
    }
  }

  /**
   * Checks if a document exists.
   * @param collection The collection name
   * @param query The query
   * @return True, if the document exists; otherwise false.
   */
  protected suspend fun documentExists(collection: String, query: JsonObject) : Boolean {
    return try {
      val result = mongoClient.findOneAwait(collection, query, json { obj() })
      result != null
    } catch (e: Throwable) {
      log.error("Unable to check document existence", e)
      false
    }
  }

  /**
   * Gets a document.
   * @param collection The collection name
   * @param query The query
   * @return The document exists; otherwise null.
   */
  protected suspend fun getDocument(collection: String, query: JsonObject): JsonObject? {
    return try {
      mongoClient.findOneAwait(collection, query, json { obj() })
    } catch (e: Throwable) {
      log.error("Unable to get document", e)
      null
    }
  }

  /**
   * Resolves a document from a specific collection to an instance of the specified type.
   * @param collection The collection name
   * @param id The document identifier
   * @return The document exists; otherwise null.
   */
  protected suspend inline fun <reified T> resolveDocument(collection: String, id: String): T? {
    return resolveDocument<T>(collection, json { obj(DEFAULT_MONGO_ID_KEY to id)})
  }

  /**
   * Resolves a document from a specific collection to an instance of the specified type.
   * @param collection The collection name
   * @param query The query
   * @return The document exists; otherwise null.
   */
  protected suspend inline fun <reified T> resolveDocument(collection: String, query: JsonObject): T? {
    return try {
      getDocument(collection, query)?.let {
        DatabindCodec.mapper().convertValue(it.flattenId(), jacksonTypeRef<T>())
      }
    } catch (e: Throwable) {
      log.error("Unable to get document or convert to the specified type", e)
      null
    }
  }

  /**
   * Gets documents from a specific collection.
   * @param collection The connection name
   * @param query The query
   * @param options The find options
   * @return The list of documents.
   */
  protected suspend fun getDocuments(collection: String,
                                     query: JsonObject = json { obj() },
                                     options: FindOptions = findOptionsOf()): List<JsonObject> {
    return try {
      mongoClient.findWithOptionsAwait(collection, query, options)
    } catch (e: Throwable) {
      log.error("Unable to get documents or convert to specified type", e)
      listOf()
    }
  }

  /**
   * Resolves documents from a specific collection to instances of the specified type.
   * @param collection The connection name
   * @param query The query
   * @param options The find options
   * @return The list of resolved instances.
   */
  protected suspend inline fun <reified T> resolveDocuments(collection: String,
                                                            query: JsonObject = json { obj() },
                                                            options: FindOptions = findOptionsOf()): List<T> {
    return try {
      mongoClient.findWithOptionsAwait(collection, query, options).map {
        DatabindCodec.mapper().convertValue(it.flattenId(), jacksonTypeRef<T>())
      }
    } catch (e: Throwable) {
      log.error("Unable to get documents or convert them to the specified type", e)
      listOf()
    }
  }

  /**
   * Sends the documents inside the specified collection.
   * @param context The routing context
   * @param collection The collection name
   */
  protected suspend inline fun <reified T> sendDocuments(context: RoutingContext, collection: String) {
    log.debug("Requested sending collection: $collection")

    try {
      val results = mongoClient.findAwait(collection, JsonObject())
      context.response().json( results.flattenId().map { JsonObject.mapFrom(it.mapTo(T::class.java)) } )
    } catch (e: Throwable) {
      log.error("Unable to get the data for the specified page ($collection)", e)
      context.response().error()
    }
  }

  /**
   * Sends the results for a specific page of a specific collection.
   * @param context The routing context
   * @param collection The collection name
   * @param query The query (default: none)
   * @param sort The sort options (default: none)
   */
  protected suspend inline fun <reified T> sendPageResults(context: RoutingContext,
                                                           collection: String,
                                                           query: JsonObject = json { obj() },
                                                           sort: JsonObject? = null) {

    log.debug("Requested page results for collection: $collection")

    try {
      val pageParam = context.queryParam(PAGE_QUERY_KEY)
      val limitParam = context.queryParam(LIMIT_QUERY_KEY)

      val page = if (pageParam.isNotEmpty()) pageParam[0].toInt() else 0
      val limit = if (limitParam.isNotEmpty()) limitParam[0].toInt() else 20
      if (page < 0 || limit <= 0) { context.response().badRequest(); return; }

      val findOptions = findOptionsOf(
        sort = sort,
        skip = page * limit,
        limit = limit
      )

      val count = mongoClient.countAwait(collection, query)


      //check for out of bound
      if (page * limit > count) { context.response().badRequest(); return; }

      val result = mongoClient.findWithOptionsAwait(collection, query, findOptions)
      val results = result.flattenId().map { it.mapTo(T::class.java) }
      val response = PageResponse<T>(page, limit, count, results)

      log.debug("Sending results for page $page (limit: $limit, count: $count)")
      context.response().json(JsonObject.mapFrom(response))
    } catch (e: Throwable) {
      log.error("Unable to get the data for the specified page ($collection)", e)
      context.response().error()
    }
  }
  //endregion
}
