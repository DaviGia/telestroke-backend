package it.unibo.telestroke.gateway.auth

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.User
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.auth.jwt.impl.JWTUser
import io.vertx.ext.jwt.JWTOptions
import io.vertx.redis.RedisClient

/**
 * Custom JWT authentication provider that uses redis as backend.
 */
class RedisJwtAuthProvider(private val redisClient: RedisClient, private val jwtAuthOptions: JWTAuthOptions) : JWTAuth {

  override fun authenticate(authInfo: JsonObject, resultHandler: Handler<AsyncResult<User>>) {
    try {
      val token = authInfo.getString("jwt")

      redisClient.get(token) {
        if (it.succeeded()) {
          if (it.result() != null) {
            val claims = JsonObject(it.result())
            val user = JWTUser(claims, jwtAuthOptions.permissionsClaimKey)
            resultHandler.handle(Future.succeededFuture(user))
          } else {
            resultHandler.handle(Future.failedFuture(IllegalArgumentException("The token is not valid")))
          }
        } else {
          resultHandler.handle(Future.failedFuture(it.cause()))
        }
      }
    } catch (e: Throwable) {
      resultHandler.handle(Future.failedFuture(e))
    }
  }

  override fun generateToken(claims: JsonObject?, options: JWTOptions?): String {
    throw NotImplementedError("RedisJwtAuthProvider must not generate tokens")
  }
}
