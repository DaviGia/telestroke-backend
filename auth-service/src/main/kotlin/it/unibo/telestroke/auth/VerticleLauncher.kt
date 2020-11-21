package it.unibo.telestroke.auth

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions

fun main() {
  val options = VertxOptions().setBlockedThreadCheckInterval(1000*60*60)
  Vertx.vertx(options).deployVerticle(AuthVerticle()) {
    if (it.succeeded()) {
      println("The verticle has been successfully deployed, with id: ${it.result()}")
    } else {
      println("Error during deployment: \n${it.cause()}")
    }
  }
}
