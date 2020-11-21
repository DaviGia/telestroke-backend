package it.unibo.telestroke.auth

import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.User
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.auth.mongo.AuthenticationException
import io.vertx.ext.auth.mongo.MongoAuth
import io.vertx.ext.jwt.JWTOptions
import io.vertx.ext.mongo.IndexOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.auth.authenticateAwait
import io.vertx.kotlin.ext.auth.mongo.insertUserAwait
import io.vertx.kotlin.ext.mongo.createIndexWithOptionsAwait
import io.vertx.kotlin.ext.mongo.findOneAwait
import io.vertx.kotlin.ext.mongo.insertAwait
import io.vertx.kotlin.redis.*
import io.vertx.redis.RedisClient
import io.vertx.redis.RedisOptions
import io.vertx.redis.op.SetOptions
import it.unibo.telestroke.auth.models.Tokens
import it.unibo.telestroke.auth.models.UserDetails
import it.unibo.telestroke.auth.models.requests.LoginRequest
import it.unibo.telestroke.auth.models.requests.LogoutRequest
import it.unibo.telestroke.auth.models.requests.RefreshRequest
import it.unibo.telestroke.auth.models.requests.RegistrationRequest
import it.unibo.telestroke.auth.models.response.LoginResponse
import it.unibo.telestroke.common.extensions.*
import it.unibo.telestroke.common.mongo.MongoVerticle
import it.unibo.telestroke.common.mongo.extensions.mongo
import it.unibo.telestroke.common.utils.HealthChecksBuilder

/**
 * Authentication service.
 */
class AuthVerticle : MongoVerticle() {

  companion object {
    private val log: Logger = LoggerFactory.getLogger(AuthVerticle::class.java)

    private const val DEFAULT_MONGODB_ID_KEY = "_id"

    private const val USER_DETAILS_COLLECTION = "user-details"
    private const val USER_DETAILS_ID_KEY = "id"

    private const val PRINCIPAL_USER_ID_KEY = "userId"

    private const val TOKEN_HEADER_KEY = "Authorization"
    private const val PREFIX_JWT_TOKEN = "Bearer"

    private const val REFRESH_TOKEN_EXPIRATION_INCREMENT = 2
  }

  //region fields
  private lateinit var mongoAuth: MongoAuth //user authentication
  private lateinit var redisClient: RedisClient //stores the active tokens

  private lateinit var jwtProvider: JWTAuth //token authentication
  private lateinit var jwtDecoder: JWTAuth //decode token data without checking expiration
  //endregion

  override suspend fun start() {
    super.start()

    //initializes auth components
    initialize()
  }

  override suspend fun stop() {
    mongoClient.close()
    redisClient.closeAwait()
    super.stop()
  }

  override suspend fun initialize() {
    super.initialize()

    //configure mongodb auth
    val authProperties = json { obj() }
    mongoAuth = MongoAuth.create(mongoClient, authProperties)

    //create unique index for user collection
    mongoClient.createIndexWithOptionsAwait(MongoAuth.DEFAULT_COLLECTION_NAME,
      json { obj(MongoAuth.DEFAULT_USERNAME_FIELD to 1) }, IndexOptions().unique(true))

    //configure redis
    val redisOptions = getConfig<RedisOptions>("/redis") ?:
    throw IllegalStateException("Redis configuration is missing")
    redisClient = RedisClient.create(vertx, redisOptions)

    //configure jwt authentication
    val authOptions = getConfig<JWTOptions>("/auth/jwt/options", false)
    val jwtKeyOptions = getConfig<PubSecKeyOptions>("/auth/jwt/key") ?:
    throw IllegalStateException("JWT key configuration is missing")

    val jwtAuthOptions = JWTAuthOptions().apply {
      pubSecKeys = listOf(jwtKeyOptions)
      authOptions?.let { setJWTOptions(it) }
    }
    jwtProvider = JWTAuth.create(vertx, jwtAuthOptions)

    //clone options and force ignore expiration
    val decoderOptions = JWTAuthOptions().apply {
      pubSecKeys = jwtAuthOptions.pubSecKeys.toMutableList()
      jwtOptions = JWTOptions(authOptions?.toJSON() ?: json { obj() }).apply { isIgnoreExpiration = true }
    }
    jwtDecoder = JWTAuth.create(vertx, decoderOptions)
  }

  override fun createRouter(baseUrl: String): Router {
    val healthChecks = HealthChecksBuilder().mongo(mongoClient).redis(redisClient).build(vertx)
    return getBaseRouter(healthChecks).apply {
      post("/$baseUrl/register").asyncHandler { handleRegister(it) }
      post("/$baseUrl/login").asyncHandler { handleLogin(it) }
      post("/$baseUrl/refresh").asyncHandler { handleRefresh(it) }
      post("/$baseUrl/logout").handler(JWTAuthHandler.create(jwtProvider)).asyncHandler { handleLogout(it) }
      post("/$baseUrl/authorize").handler(JWTAuthHandler.create(jwtProvider)).handler { handleAuthorize(it) }
    }
  }

  //region handlers
  /**
   * Handles the registration of a new user.
   * @param context The routing context
   */
  private suspend fun handleRegister(context: RoutingContext) {
    log.debug("Requested registration...")

    try {
      val request = context.bodyAsJson.mapTo(RegistrationRequest::class.java)
      val id = mongoAuth.insertUserAwait(request.username, request.password, listOf(), listOf())

      val userDetails = json {
        obj(DEFAULT_MONGODB_ID_KEY to id)
      }.mergeIn(JsonObject.mapFrom(request.details), true)
      mongoClient.insertAwait(USER_DETAILS_COLLECTION, userDetails)

      log.debug("Registered new user with id: $id")
      context.response().success()
    } catch (e: IllegalArgumentException) {
      log.error("Failed to parse registration details", e)
      context.response().badRequest()
    } catch (e: Throwable) {
      log.error("Failed to save user details", e)
      context.response().error()
    }
  }

  /**
   * Handles the login of a user.
   * @param context The routing context
   */
  private suspend fun handleLogin(context: RoutingContext) {
    log.debug("Handling login request...")

    try {
      val request = context.bodyAsJson.mapTo(LoginRequest::class.java)
      val authInfo = json {
        obj(
          MongoAuth.DEFAULT_USERNAME_FIELD to request.username,
          MongoAuth.DEFAULT_PASSWORD_FIELD to request.password
        )
      }

      val user = mongoAuth.authenticateAwait(authInfo)
      val userId = user.principal().getString(DEFAULT_MONGODB_ID_KEY)
      val username = user.principal().getString(MongoAuth.DEFAULT_USERNAME_FIELD)
      val userDetails = mongoClient.findOneAwait(USER_DETAILS_COLLECTION, json {
        obj(DEFAULT_MONGODB_ID_KEY to userId)
      }, null) ?: throw IllegalStateException("No user details found for specified user id")

      val claims = stripPrincipal(user).apply {
        put("details", userDetails.purgeId())
      }

      //create tokens
      val tokens = createTokens(claims)

      //reply with claims
      val details = userDetails.put(USER_DETAILS_ID_KEY, userId).put(MongoAuth.DEFAULT_USERNAME_FIELD, username)
      val response = LoginResponse(details.mapTo(UserDetails::class.java), tokens.token, tokens.refreshToken)

      context.response().json(JsonObject.mapFrom(response))
      log.debug("Login succeeded for user: $username")

    } catch (e: AuthenticationException) {
      log.error("Incorrect credentials provided", e)
      context.response().unauthorized("Invalid credentials")
    } catch (e: IllegalArgumentException) {
      log.error("Failed to parse registration details", e)
      context.response().badRequest()
    } catch (e: Throwable) {
      log.error("Failed to handle login", e)
      context.response().error()
    }
  }

  /**
   * Handles the logout of a user.
   * @param context The routing context
   */
  private suspend fun handleLogout(context: RoutingContext) {
    log.debug("Handling logout request...")

    try {
      val request = context.bodyAsJson.mapTo(LogoutRequest::class.java)
      val jwtToken = context.request().getHeader(TOKEN_HEADER_KEY).removePrefix(PREFIX_JWT_TOKEN).trimStart()

      val relativeToken = redisClient.getAwait(request.refreshToken)
      if (relativeToken != jwtToken) throw IllegalArgumentException("Refresh token does not correspond to the JWT token")

      //revoke jwt and refresh token (gateway authentication uses redis as token store, so no further action is required)
      redisClient.delManyAwait(listOf(jwtToken, request.refreshToken))

      context.response().success()
      log.debug("Logout succeeded")

    } catch (e: IllegalArgumentException) {
      log.error("Failed to parse request", e)
      context.response().badRequest()
    } catch (e: Throwable) {
      log.error("Failed to handle logout", e)
      context.response().error()
    }
  }

  /**
   * Handles the refresh of a token.
   * @param context The routing context
   */
  private suspend fun handleRefresh(context: RoutingContext) {

    try {
      val request = context.bodyAsJson.mapTo(RefreshRequest::class.java)

      //get original principal
      val principal = jwtDecoder.authenticateAwait(json {
        obj("jwt" to request.token)
      }).principal()

      //check if refresh token is relative to the jwt token
      val error = redisClient.getAwait(request.refreshToken)?.let {
        if (it != request.token) {
          log.error("Refresh token is not relative to the specified jwt token")
          true
        } else false
      } ?: run {
        log.error("Refresh token do not exists or it is expires")
        true
      }

      if (error) {
        //if any error occurs revoke refresh token
        redisClient.delAwait(request.refreshToken)
        context.response().unauthorized()
        log.debug("Token refresh failed")
      } else {
        //revoke jwt and refresh token
        redisClient.delManyAwait(listOf(request.token, request.refreshToken))
        //create tokens using the same principal
        val tokens = createTokens(principal)
        context.response().json(JsonObject.mapFrom(tokens))
        log.debug("Token refresh succeeded")
      }
    } catch (e: IllegalArgumentException) {
      log.error("Failed to parse request", e)
      context.response().badRequest()
    } catch (e: Throwable) {
      log.error("Failed to handle refresh", e)
      context.response().error()
    }
  }

  /**
   * Handles the authorization via token.
   * @param context The routing context
   */
  private fun handleAuthorize(context: RoutingContext) {
    log.debug("Handling authorization...")

    try {
      context.response().json(context.user().principal())
      log.debug("Authorization succeeded")
    } catch (e: AuthenticationException) {
      log.error("Incorrect credentials provided", e)
      context.response().unauthorized()
    } catch (e: IllegalArgumentException) {
      log.error("Failed to parse registration details", e)
      context.response().badRequest()
    } catch (e: Throwable) {
      log.error("Failed to handle login", e)
      context.response().error()
    }
  }
  //endregion

  //region helpers
  /**
   * Strips sensitive data from the principal object.
   * @param user The mongodb user object
   * @return The json object
   */
  private fun stripPrincipal(user: User): JsonObject {
    return user.principal().apply {
      remove(MongoAuth.DEFAULT_SALT_FIELD)
      remove(MongoAuth.DEFAULT_PASSWORD_FIELD)
      flattenId(PRINCIPAL_USER_ID_KEY)
    }
  }

  /**
   * Creates a new JWT token and the relative.
   * @param claims The object that represents the claims
   */
  private suspend fun createTokens(claims: JsonObject): Tokens {
    //create tokens
    val tokenOptions = getConfig<JWTOptions>("/auth/jwt/options", false)
      ?: throw IllegalStateException("Unable to parse JWT options")

    val userId = claims.getString(PRINCIPAL_USER_ID_KEY)
      ?: throw IllegalArgumentException("Unable to get user id from claims")

    val token = jwtProvider.generateToken(claims, tokenOptions)
    val refreshToken = jwtProvider.generateToken(json { obj(PRINCIPAL_USER_ID_KEY to userId) })

    //save token to redis
    val setTokenOptions = SetOptions()
    val setRefreshTokenOptions = SetOptions()
    getConfig<Long>("/auth/jwt/options/expiresInSeconds")?.let {
      setTokenOptions.setEX(it)
      setRefreshTokenOptions.setEX(it * REFRESH_TOKEN_EXPIRATION_INCREMENT)
    }

    //save token and refresh token (they will be removed if expired)
    val jwtData = jwtProvider.authenticateAwait(json { obj("jwt" to token)}).principal()
    redisClient.setWithOptionsAwait(token, jwtData.encode(), setTokenOptions)
    redisClient.setWithOptionsAwait(refreshToken, token, setRefreshTokenOptions)

    return Tokens(token, refreshToken)
  }
  //endregion
}
