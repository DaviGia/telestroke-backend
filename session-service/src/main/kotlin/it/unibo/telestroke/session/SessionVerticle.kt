package it.unibo.telestroke.session

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.toChannel
import io.vertx.kotlin.ext.mongo.*
import it.unibo.telestroke.common.extensions.*
import it.unibo.telestroke.common.models.Peer
import it.unibo.telestroke.common.models.UserInfo
import it.unibo.telestroke.common.mongo.MongoVerticle
import it.unibo.telestroke.common.mongo.models.PageResponse
import it.unibo.telestroke.session.models.checklist.Checklist
import it.unibo.telestroke.session.models.info.*
import it.unibo.telestroke.session.models.request.CreateSessionRequest
import it.unibo.telestroke.session.models.response.ClosingErrorResponse
import it.unibo.telestroke.session.models.session.Action
import it.unibo.telestroke.session.models.session.PhaseResult
import it.unibo.telestroke.session.models.session.Session
import it.unibo.telestroke.session.models.session.SessionPhase
import it.unibo.telestroke.session.models.template.Template
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.toList
import java.util.*

/**
 * The session verticle.
 */
class SessionVerticle : MongoVerticle() {

  companion object {
    private val log: Logger = LoggerFactory.getLogger(SessionVerticle::class.java)

    private const val PAGINATION_API_ROUTE = "pages"

    private const val ID_PARAM_KEY = "id"
    private const val CHECKLIST_ID_PARAM_KEY = "checklistId"

    private const val GET_SESSIONS_FILTER_KEY = "filter"
    private const val SESSION_FILTER_ACTIVE = "active"
    private const val SESSION_FILTER_ENDED = "ended"

    private const val SESSION_ID_KEY = "id"
    private const val SESSION_END_DATE_KEY = "endDate"

    private const val PEER_CURRENT_SESSION_KEY = "currentSession"
  }

  override suspend fun start() {
    //register kotlin module with custom configuration before calling super start (if already defined base verticle won't register it)
    DatabindCodec.mapper().registerModule(KotlinModule(nullisSameAsDefault = true, nullToEmptyCollection = true))
    //initialize base verticle
    super.start()
  }

  override fun createRouter(baseUrl: String): Router {
    val healthChecks = mongoHealthCheckBuilder().build(vertx)

    return getBaseRouter(healthChecks).apply {
      get("/$baseUrl/sessions/info").asyncHandler { handleGetSessionsInfo(it) }
      get("/$baseUrl/sessions/info/$PAGINATION_API_ROUTE").asyncHandler { handleGetSessionsInfoPage(it) }
      get("/$baseUrl/sessions/info/:$ID_PARAM_KEY").asyncHandler { handleGetSessionInfo(it) }
      get("/$baseUrl/sessions").asyncHandler { handleGetSessions(it) }
      get("/$baseUrl/sessions/$PAGINATION_API_ROUTE").asyncHandler { handleGetSessionsPage(it) }
      get("/$baseUrl/sessions/:$ID_PARAM_KEY").asyncHandler { handleGetSession(it) }
      post("/$baseUrl/sessions").asyncHandler { handleCreateSession(it) }
      put("/$baseUrl/sessions/:$ID_PARAM_KEY/checklist/:$CHECKLIST_ID_PARAM_KEY/action").asyncHandler { handleUpsertSessionAction(it) }
      post("/$baseUrl/sessions/:$ID_PARAM_KEY/close").asyncHandler { handleCloseSession(it) }
      post("/$baseUrl/sessions/:$ID_PARAM_KEY/abort").asyncHandler { handleAbortSession(it) }
      get("/$baseUrl/templates").asyncHandler { handleGetTemplates(it) }
      get("/$baseUrl/templates/$PAGINATION_API_ROUTE").asyncHandler { handleGetTemplatesPage(it) }
      get("/$baseUrl/templates/:$ID_PARAM_KEY").asyncHandler { handleGetTemplate(it) }
      get("/$baseUrl/checklists").asyncHandler { handleGetChecklists(it) }
      get("/$baseUrl/checklists/$PAGINATION_API_ROUTE").asyncHandler { handleGetChecklistsPage(it) }
      get("/$baseUrl/checklists/:$ID_PARAM_KEY").asyncHandler { handleGetChecklist(it) }
      get("/$baseUrl/users/:$ID_PARAM_KEY").asyncHandler { handleGetUserInfo(it) }
    }
  }

  //region handlers

  //create a new session
  private suspend fun handleCreateSession(context: RoutingContext) {
    log.debug("Requested session creation...")

    try {
      val request = context.bodyAsJson.mapTo(CreateSessionRequest::class.java)

      //check template and user exists
      if (!documentExists(TEMPLATES_COLLECTION, request.template)) {
        log.error("Unable to create session because specified template was not found")
        context.response().notFound("Template not found")
        return
      }

      if (!documentExists(USERS_COLLECTION, request.specialist)) {
        log.error("Unable to create session because specified specialist was not found")
        context.response().notFound("Specialist not found")
        return
      }

      //get specified peer
      val peerQuery = json { obj(DEFAULT_MONGO_ID_KEY to request.peerId, "currentSession" to null) }
      val peer = resolveDocument<Peer>(PEERS_COLLECTION, peerQuery)

      //check peer status
      if (peer == null) {
        //fail if the peer does not exists
        log.error("Unable to create session because specified peer was not found")
        context.response().notFound("Operator not found")
        return
      } else if ( peer.currentSession != null) {
        //fail if the peer is busy
        log.error("Unable to create session because specified peer was not found")
        context.response().badRequest("Operator is busy")
        return
      }

      //fetch template object
      val template = mongoClient.findOneAwait(TEMPLATES_COLLECTION, json {obj(DEFAULT_MONGO_ID_KEY to request.template)}, json { obj() })
        ?: throw IllegalStateException("Unable to fetch template data")

      //build session object
      val newSession = Session(request.specialist, request.operator, template.flattenId().mapTo(Template::class.java))
      val newSessionObj = JsonObject.mapFrom(newSession).purgeId(SESSION_ID_KEY)

      mongoClient.insertAwait(SESSIONS_COLLECTION, newSessionObj)?.let {
        //mark peer as busy for this session
        mongoClient.findOneAndUpdateAwait(PEERS_COLLECTION, peerQuery,
          json { obj("\$set" to obj(PEER_CURRENT_SESSION_KEY to it))})?.let {
          log.debug("Successfully marked operator as busy with the newly created session")
        } ?: throw IllegalStateException("Cannot set operator current session id")
      }

      context.response().json(newSessionObj.flattenId())
      log.debug("Successfully created session")
    } catch (e: Throwable) {
      log.error("Unable to create session", e)
      context.response().error()
    }
  }

  //close a session
  private suspend fun handleCloseSession(context: RoutingContext) {
    log.debug("Requested session closing...")

    try {
      val sessionId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }
      val session = resolveDocument<Session>(SESSIONS_COLLECTION, sessionId) ?: run {
        log.error("Unable to add action to session because specified session was not found")
        context.response().notFound()
        return
      }

      //region Check for errors

      //define mongodb aggregation pipeline
      val aggregateOptions = aggregateOptionsOf()
      val pipeline = json {
          array(
            obj("\$match" to obj(DEFAULT_MONGO_ID_KEY to session.template)),
            obj(
              "\$lookup" to obj(
                "from" to CHECKLISTS_COLLECTION,
                "localField" to "phases.checklist",
                "foreignField" to DEFAULT_MONGO_ID_KEY,
                "as" to "checklists"
              )
            )
          )
        }

      val cursor = mongoClient.aggregateWithOptions(TEMPLATES_COLLECTION, pipeline, aggregateOptions)
      val receiver = cursor.fetch(1).toChannel(vertx) //create receive channel from pipe in order to read the value
      val aggregationResult = receiver.receive() //await the first value

      val checklists = aggregationResult.getJsonArray("checklists")?.filterIsInstance<JsonObject>()
        ?.map { it.flattenId().mapTo(Checklist::class.java) }
        ?: throw IllegalArgumentException("Aggregation result cannot be parsed")

      //retrieve errors for each phase
      val errors = checklists.map { Pair(it, session.phases.find { i -> i.checklist == it.id })} //pair phase with checklist
        .map { Pair(it.first, it.second ?: SessionPhase(it.first.id, mutableListOf(), listOf())) } //normalize pair
        .map { it.second.validate(it.first) } //perform validation

      //return errors
      val errorsCount = errors.sumBy { it.missing.size + it.invalid.size }
      if (errorsCount > 0) {
        log.error("Unable to close session because errors were found (count: $errorsCount)")
        context.response().error(JsonObject.mapFrom(ClosingErrorResponse(errorsCount, errors)))
        return
      }
      //endregion

      //region update results
      val results = mutableMapOf<Int, MutableList<PhaseResult>>()
      //compute all results
      checklists.forEachIndexed { index, checklist ->
        val phase = session.phases.find { it.checklist == checklist.id }!!
        val computedResults = phase.computeResults(checklist)

        if (results.containsKey(index)) {
          results[index]!!.addAll(computedResults)
        } else {
          results[index] = computedResults.toMutableList()
        }
      }

      //build query
      val updateQueryItems = results.map {
        val items = it.value.map { i -> JsonObject.mapFrom(i) }.toTypedArray()
        Pair("phases.${it.key}.results", json { array(*items) })
      }.toTypedArray()

      //endregion

      //TODO: call the analysis service and return a suggestion for the iter based on the session parameters

      //unbind operator
      mongoClient.findOneAndUpdateAwait(PEERS_COLLECTION,
        json { obj(PEER_CURRENT_SESSION_KEY to sessionId) },
        json { obj("\$set" to obj(PEER_CURRENT_SESSION_KEY to null)) })
      log.debug("Successfully freed operator from session")

      val query = json { obj(DEFAULT_MONGO_ID_KEY to sessionId) }
      val update = json { obj("\$set" to obj(SESSION_END_DATE_KEY to Date().time, *updateQueryItems)) }

      //close session
      mongoClient.findOneAndUpdateAwait(SESSIONS_COLLECTION, query, update)?.let {
        context.response().json(it)
        log.debug("Successfully closed session")
      } ?: throw IllegalStateException("Unable to update session document")

    } catch (e: Throwable) {
      log.error("Unable to close session", e)
      context.response().error()
    }
  }

  //abort session
  private suspend fun handleAbortSession(context: RoutingContext) {
    log.debug("Requested session abort...")

    try {
      val sessionId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      if (!documentExists(SESSIONS_COLLECTION, sessionId)) {
        log.error("Unable to abort session because specified session was not found")
        context.response().notFound()
        return
      }

      //unbind operator
      mongoClient.findOneAndUpdateAwait(PEERS_COLLECTION,
        json { obj(PEER_CURRENT_SESSION_KEY to sessionId) },
        json { obj("\$set" to obj(PEER_CURRENT_SESSION_KEY to null)) })
      context.response().success()
      log.debug("Successfully aborted session")

    } catch (e: Throwable) {
      log.error("Unable to abort session", e)
      context.response().error()
    }
  }

  //add/update action to session
  private suspend fun handleUpsertSessionAction(context: RoutingContext) {
    log.debug("Received add new action to session request...")

    try {
      val sessionId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }
      val checklistId = context.pathParam(CHECKLIST_ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      //region Checks

      //check session exists
      val session = resolveDocument<Session>(SESSIONS_COLLECTION, sessionId) ?: run {
        log.error("Unable to add action to session because specified session was not found")
        context.response().notFound("Session not found")
        return
      }

      //check checklist exists
      val checklist = resolveDocument<Checklist>(CHECKLISTS_COLLECTION, checklistId) ?: run {
        log.error("Unable to add action to session because specified checklist was not found")
        context.response().notFound("Checklist not found")
        return
      }

      //check session has specified checklist
      if (!session.phases.any { it.checklist == checklistId }) {
        log.error("Unable to add action because specified checklist is not present in the session")
        context.response().badRequest("Checklist does not exists for specified session")
        return
      }

      //check session not closed
      if (session.endDate != null) {
        log.error("Unable to add action to session because the specified session is closed")
        context.response().badRequest("Session is closed")
        return
      }
      //endregion

      //parse request body
      val action = context.bodyAsJson.mapTo(Action::class.java) ?: run { context.response().badRequest(); return }
      val actionObj = JsonObject.mapFrom(action)

      //check input value is correct
      checklist.steps.find { it.id == action.step }?.fields?.find { it.id == action.field }?.let {
        if (!it.validateAction(action)) {
          log.error("Action field value validation failed")
          context.response().badRequest("Incorrect value")
          return
        }
      } ?: run {
        log.error("Unable to validate action field value because the referenced field was not found")
        context.response().badRequest("Field does not exists")
        return
      }

      //add action and compute results
      val oldPhase = session.phases.find { it.checklist == checklist.id }!!
      val actions = oldPhase.actions.filterNot { it.step == action.step && it.field == action.field }.toMutableList()
      actions.add(action)
      val newPhase = SessionPhase(oldPhase.checklist, actions, listOf())
      val computedResults = json { array(*newPhase.computeResults(checklist).map { JsonObject.mapFrom(it) }.toTypedArray()) }

      //Determine if the action already exists
      val findActionQuery = json {
        obj(
          DEFAULT_MONGO_ID_KEY to sessionId,
          "phases" to obj (
            "\$elemMatch" to obj(
              "checklist" to checklistId,
              "actions" to obj(
                "\$elemMatch" to obj(
                  "step" to action.step,
                  "field" to action.field
                )
              )
            )
          )
        )
      }
      val actionExists = documentExists(SESSIONS_COLLECTION, findActionQuery)

      //create
      val command = if (actionExists) {
        json  {
          obj(
            "findAndModify" to SESSIONS_COLLECTION,
            "query" to findActionQuery,
            "update" to obj("\$set" to obj(
                "phases.$[phase].actions.$[action]" to actionObj,
                "phases.$[phase].results" to computedResults
              )
            ),
            "arrayFilters" to array(
              obj("phase.checklist" to checklistId),
              obj(
                "action.step" to action.step,
                "action.field" to action.field
              )
            )
          )
        }
      } else {
        json  {
          obj(
            "findAndModify" to SESSIONS_COLLECTION,
            "query" to json {
              obj(
                DEFAULT_MONGO_ID_KEY to sessionId,
                "phases" to obj(
                  "\$elemMatch" to obj("checklist" to checklistId)
                )
              )
            },
            "update" to obj(
              "\$addToSet" to obj("phases.$.actions" to actionObj),
              "\$set" to obj("phases.$.results" to computedResults)
            )
          )
        }
      }

      //TODO: use arrayFilters inside UpdateOptions with findAndUpdateWithOptionsAwait (when available)

      //execute command
      mongoClient.runCommandAwait("findAndModify", command)?.let {
        context.response().json(it.getJsonObject("value"))
        log.debug("Successfully added action to session")
      } ?: throw IllegalStateException("Unable to add action: the command result was unreadable")

    } catch (e: Throwable) {
      log.error("Unable to add action to session", e)
      context.response().error()
    }
  }

  //get a specific session
  private suspend fun handleGetSession(context: RoutingContext) {
    log.debug("Requested a session...")

    try {
      val sessionId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      resolveDocument<Session>(SESSIONS_COLLECTION, sessionId)?.let {
        context.response().json(JsonObject.mapFrom(it))
        log.debug("Successfully sent the session")
      } ?: run {
        log.error("Unable to find session with specified id")
        context.response().notFound()
      }
    } catch (e: Throwable) {
      log.error("Unable to get session", e)
      context.response().error()
    }
  }

  //retrieves all sessions (?filter=active|ended)
  private suspend fun handleGetSessions(context: RoutingContext) {
    log.debug("Requested sessions...")

    try {
      val findOptions = findOptionsOf(sort = json { obj("startDate" to -1) })

      val filter = context.queryParam(GET_SESSIONS_FILTER_KEY)
      val query = when(filter.firstOrNull()) {
        SESSION_FILTER_ACTIVE -> json { obj("endDate" to null) }
        SESSION_FILTER_ENDED -> json { obj("endDate" to obj("\$ne" to null)) }
        else -> json { obj() }
      }

      val result = mongoClient.findWithOptionsAwait(SESSIONS_COLLECTION, query, findOptions)
      val sessions = result.flattenId().map { it.mapTo(Session::class.java) }
      context.response().json(sessions.map { JsonObject.mapFrom(it) })
      log.debug("Successfully sent sessions")
    } catch (e: Throwable) {
      log.error("Unable to get sessions", e)
      context.response().error()
    }
  }

  //retrieves a sessions page
  private suspend fun handleGetSessionsPage(context: RoutingContext) {
    log.debug("Requested sessions page...")

    val filter = context.queryParam(GET_SESSIONS_FILTER_KEY)
    val query = when(filter.firstOrNull()) {
      SESSION_FILTER_ACTIVE -> json { obj("endDate" to null) }
      SESSION_FILTER_ENDED -> json { obj("endDate" to obj("\$ne" to null)) }
      else -> json { obj() }
    }
    val sort = json { obj("startDate" to -1) }
    sendPageResults<Session>(context, SESSIONS_COLLECTION, query, sort)
  }

  //get a specific session info
  private suspend fun handleGetSessionInfo(context: RoutingContext) {
    log.debug("Requested session info...")

    try {
      //parameters
      val sessionId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      //perform aggregation
      val options = aggregateOptionsOf(maxAwaitTime = 0, maxTime = 0)
      val pipeline = json {
        array(
          obj("\$match" to obj(DEFAULT_MONGO_ID_KEY to sessionId)),
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "specialist",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "specialist")
          ),
          obj("\$unwind" to "\$specialist"), //resolve specialist
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "operator",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "operator")
          ),
          obj("\$unwind" to "\$operator"), //resolve operator
          obj(
            "\$lookup" to obj(
              "from" to TEMPLATES_COLLECTION,
              "localField" to "template",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "template")
          ),
          obj("\$unwind" to "\$template"), //resolve template
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "template.author",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "template.author")
          ),
          obj("\$unwind" to "\$template.author"), //resolve template author
          obj(
            "\$lookup" to obj(
              "from" to CHECKLISTS_COLLECTION,
              "localField" to "phases.checklist",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "checklists")  //resolve checklists array (do not unwind, array is needed)
          ),
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "checklists.author",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "authors")  //resolve authors array (do not unwind, array is needed)
          ),
          obj("\$project" to obj("template" to obj("phases" to 0)))  //remove unwanted properties
        )
      }

      val cursor = mongoClient.aggregateWithOptions(SESSIONS_COLLECTION, pipeline, options)
      val receiver = cursor.fetch(1).toChannel(vertx)

      val raw = receiver.receive()

      val authors = raw.getJsonArray("authors").filterIsInstance<JsonObject>().map {
        Pair(it.getString(DEFAULT_MONGO_ID_KEY), it.mapTo(UserInfo::class.java))
      }
      val checklists = raw.getJsonArray("checklists").filterIsInstance<JsonObject>().map { it.flattenId().mapTo(Checklist::class.java) }
      val phases = raw.getJsonArray("phases").filterIsInstance<JsonObject>().map { it.flattenId().mapTo(SessionPhase::class.java) }
      val sessionInfo = raw.flattenId().mapTo(SessionInfo::class.java)

      val completeSessionInfo = CompleteSessionInfo(sessionInfo, phases
        .map { Triple(it.actions, it.results, checklists.find { i -> i.id == it.checklist }!!) }
        .map { group ->
          val actions = group.first
          val results = group.second
          val checklist = group.third
          val author = authors.find { i -> i.first == checklist.author }!!

          val checklistInfo = ChecklistInfo(checklist, author.second)

          val actionsInfo = actions.map {
            val step = checklist.steps.find { step -> step.id == it.step }!!
            val field = step.fields.find { field -> field.id == it.field }!!

            ActionInfo(it.value, field.type, field.constraints.valueType, StepInfo(step), FieldInfo(field))
          }

          val resultsInfo = results.map { phaseResult ->
            val result = checklist.results.find { r -> r.id == phaseResult.id }!!
            val fields = checklist.steps.flatMap{ it.fields }
                                        .filter { it.references.contains(result.targetField) }
                                        .map { FieldInfo(it) }
            ResultInfo(result, fields, phaseResult.value)
          }

          PhaseInfo(checklistInfo, actionsInfo, resultsInfo)
        })

      context.response().json(JsonObject.mapFrom(completeSessionInfo))
      log.debug("Successfully sent session info")

    } catch (e: ClosedReceiveChannelException) {
      context.response().notFound()
    } catch (e: Throwable) {
      log.error("Unable to get session info", e)
      context.response().error()
    }
  }

  //retrieves sessions info
  private suspend fun handleGetSessionsInfo(context: RoutingContext) {
    log.debug("Requested sessions info...")

    try {
      val options = aggregateOptionsOf(batchSize = 10000, maxAwaitTime = 0, maxTime = 0)
      val pipeline = json {
        array(
          obj("\$sort" to obj("startDate" to -1)),
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "specialist",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "specialist")
          ),
          obj("\$unwind" to "\$specialist"), //resolve specialist
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "operator",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "operator")
          ),
          obj("\$unwind" to "\$operator"), //resolve operator
          obj(
            "\$lookup" to obj(
              "from" to TEMPLATES_COLLECTION,
              "localField" to "template",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "template")
          ),
          obj("\$unwind" to "\$template"), //resolve template
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "template.author",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "template.author")
          ),
          obj("\$unwind" to "\$template.author"), //resolve template author
          obj("\$project" to obj(
              "template.phases" to 0,
              "phases" to 0
            )
          ) //remove unwanted properties
        )
      }

      val cursor = mongoClient.aggregateWithOptions(SESSIONS_COLLECTION, pipeline, options)
      val receiver = cursor.toChannel(vertx)

      val results = receiver.toList()
        .map { it.mapTo(SessionInfo::class.java) }
        .map { JsonObject.mapFrom(it)}

      context.response().json(results)
      log.debug("Successfully sent sessions info")

    } catch (e: Throwable) {
      log.error("Unable to get the data for the specified page", e)
      context.response().error()
    }
  }

  //retrieves a sessions info page
  private suspend fun handleGetSessionsInfoPage(context: RoutingContext) {
    log.debug("Requested sessions info page...")

    try {
      //parameters
      val pageParam = context.queryParam(PAGE_QUERY_KEY)
      val limitParam = context.queryParam(LIMIT_QUERY_KEY)
      val page = if (pageParam.isNotEmpty()) pageParam[0].toInt() else 0
      val limit = if (limitParam.isNotEmpty()) limitParam[0].toInt() else 20
      if (page < 0 || limit <= 0) { context.response().badRequest(); return; }

      //check for out of bound
      val count = mongoClient.countAwait(SESSIONS_COLLECTION, json { obj() })
      if (page * limit > count) { context.response().badRequest(); return; }

      log.debug("Resolving results for page $page (limit: $limit, count: $count)")

      val options = aggregateOptionsOf(maxAwaitTime = 0, maxTime = 0)
      val pipeline = json {
        array(
          obj("\$sort" to obj("startDate" to -1)),
          obj("\$skip" to page * limit),
          obj("\$limit" to limit),
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "specialist",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "specialist")
          ),
          obj("\$unwind" to "\$specialist"), //resolve specialist
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "operator",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "operator")
          ),
          obj("\$unwind" to "\$operator"), //resolve operator
          obj(
            "\$lookup" to obj(
              "from" to TEMPLATES_COLLECTION,
              "localField" to "template",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "template")
          ),
          obj("\$unwind" to "\$template"), //resolve template
          obj(
            "\$lookup" to obj(
              "from" to USERS_DETAILS_COLLECTION,
              "localField" to "template.author",
              "foreignField" to DEFAULT_MONGO_ID_KEY,
              "as" to "template.author")
          ),
          obj("\$unwind" to "\$template.author"), //resolve template author
          obj("\$project" to obj(
              "template.phases" to 0,
              "phases" to 0
            )
          ) //remove unwanted properties
        )
      }

      val cursor = mongoClient.aggregateWithOptions(SESSIONS_COLLECTION, pipeline, options)
      val receiver = cursor.toChannel(vertx)

      val results = receiver.toList().map { it.mapTo(SessionInfo::class.java) }
      val response = PageResponse<SessionInfo>(page, limit, count, results)

      context.response().json(JsonObject.mapFrom(response))
      log.debug("Successfully sent sessions info page")
    } catch (e: Throwable) {
      log.error("Unable to get the data for the specified page", e)
      context.response().error()
    }
  }

  //retrieves a checklist
  private suspend fun handleGetChecklist(context: RoutingContext) {
    log.debug("Requested a checklist...")

    try {
      val checklistId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      resolveDocument<Checklist>(CHECKLISTS_COLLECTION, checklistId)?.let {
        context.response().json(JsonObject.mapFrom(it))
        log.debug("Successfully sent checklist")
      } ?: run {
        log.error("Unable to find checklist with specified id")
        context.response().notFound()
      }
    } catch (e: Throwable) {
      log.error("Unable to get checklist", e)
      context.response().error()
    }
  }

  //retrieves all checklists
  private suspend fun handleGetChecklists(context: RoutingContext) {
    log.debug("Requested checklists...")

    try {
      sendDocuments<Checklist>(context, CHECKLISTS_COLLECTION)
    } catch (e: Throwable) {
      log.error("Unable to get checklists", e)
      context.response().error()
    }
  }

  //retrieves a checklists page
  private suspend fun handleGetChecklistsPage(context: RoutingContext) {
    log.debug("Requested checklists page...")
    sendPageResults<Checklist>(context, CHECKLISTS_COLLECTION)
  }

  //retrieves a template
  private suspend fun handleGetTemplate(context: RoutingContext) {
    log.debug("Requested a template...")

    try {
      val templateId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      resolveDocument<Template>(TEMPLATES_COLLECTION, templateId)?.let {
        context.response().json(JsonObject.mapFrom(it))
        log.debug("Successfully sent template")
      } ?: run {
        log.error("Unable to find checklist with specified id")
        context.response().notFound()
      }
    } catch (e: Throwable) {
      log.error("Unable to get template", e)
      context.response().error()
    }
  }

  //retrieves all templates
  private suspend fun handleGetTemplates(context: RoutingContext) {
    log.debug("Requested templates...")

    try {
      sendDocuments<Template>(context, TEMPLATES_COLLECTION)
    } catch (e: Throwable) {
      log.error("Unable to get templates", e)
      context.response().error()
    }
  }

  //retrieves a checklists page
  private suspend fun handleGetTemplatesPage(context: RoutingContext) {
    log.debug("Requested templates page...")
    sendPageResults<Template>(context, TEMPLATES_COLLECTION)
  }

  //retrieves user information
  private suspend fun handleGetUserInfo(context: RoutingContext) {
    log.debug("Requested a user details...")

    try {
      val userId = context.pathParam(ID_PARAM_KEY) ?: run { context.response().badRequest(); return }

      resolveDocument<UserInfo>(USERS_DETAILS_COLLECTION, userId)?.let {
        context.response().json(JsonObject.mapFrom(it))
        log.debug("Successfully sent user details")
      } ?: run {
        log.error("Unable to find user details with specified id")
        context.response().notFound()
      }
    } catch (e: Throwable) {
      log.error("Unable to get user details", e)
      context.response().error()
    }
  }
  //endregion
}
