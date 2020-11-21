package it.unibo.telestroke.common.extensions

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

private const val CONTENT_TYPE_HEADER_KEY = "Content-Type"
private const val APPLICATION_JSON_MIME = "application/json"

/**
 * Ends the response with a 200 OK and writes the input json object in the body, if provided.
 */
fun HttpServerResponse.success() = apply {
  setHttpStatus(HttpResponseStatus.OK)
  end()
}

/**
 * Sends the response to the client by placing the input json-encoded string in the body.
 * @param value The formatted json as string
 */
fun HttpServerResponse.json(value: String) = apply {
  setHttpStatus(HttpResponseStatus.OK)
  putHeader(CONTENT_TYPE_HEADER_KEY, APPLICATION_JSON_MIME)
  end(value)
}

/**
 * Sends the response to the client by placing the serialized input json object in the body.
 * @param obj The json object
 */
fun HttpServerResponse.json(obj: JsonObject) = apply {
  json(obj.toString())
}

/**
 * Sends the response to the client by placing a serialized json array that contains the input list of json objects in the body.
 * @param objects The list of json objects
 */
fun HttpServerResponse.json(objects: List<JsonObject>) = apply {
  json(JsonArray(objects).toString())
}

/**
 * Sends the response to the client by placing a serialized json array in the body.
 * @param array The json array
 */
fun HttpServerResponse.json(array: JsonArray) = apply {
  json(array.toString())
}

/**
 * Ends the response with a 400 Bad Request.
 * @param body The body (default: empty)
 */
fun HttpServerResponse.noContent(body: String? = null) = apply {
  setHttpStatus(HttpResponseStatus.NO_CONTENT)
  endResponse(body)
}

/**
 * Ends the response with a 400 Bad Request.
 * @param body The body (default: empty)
 */
fun HttpServerResponse.badRequest(body: String? = null) = apply {
  setHttpStatus(HttpResponseStatus.BAD_REQUEST)
  endResponse(body)
}

/**
 * Ends the response with a 401 Unauthorized.
 * @param body The body (default: empty)
 */
fun HttpServerResponse.unauthorized(body: String? = null) = apply {
  setHttpStatus(HttpResponseStatus.UNAUTHORIZED)
  endResponse(body)
}

/**
 * Ends the response with a 404 Not Found.
 * @param body The body (default: empty)
 */
fun HttpServerResponse.notFound(body: String? = null) = apply {
  setHttpStatus(HttpResponseStatus.NOT_FOUND)
  endResponse(body)
}

/**
 * Ends the response with a 409 Conflict.
 * @param body The body (default: empty)
 */
fun HttpServerResponse.conflict(body: String? = null) = apply {
  setHttpStatus(HttpResponseStatus.CONFLICT)
  endResponse(body)
}

/**
 * Ends the response with a 500 Internal Server error with a string in the body.
 * @param body The body (default: empty)
 */
fun HttpServerResponse.error(body: String? = null) = apply {
  setHttpStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR)
  endResponse(body)
}

/**
 * Ends the response with a 500 Internal Server error with the message of the input throwable in the body, if declared.
 * @param throwable The throwable
 */
fun HttpServerResponse.error(throwable: Throwable) = apply {
  setHttpStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR)
  end(throwable.message)
}

/**
 * Ends the response with a 500 Internal Server error with a json object in the body.
 * @param body The json object
 */
fun HttpServerResponse.error(body: JsonObject) = apply {
  setHttpStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR)
  putHeader(CONTENT_TYPE_HEADER_KEY, APPLICATION_JSON_MIME)
  end(body.toString())
}

/**
 * Sets the status code and message to the server response.
 * @param responseStatus The response status
 */
private fun HttpServerResponse.setHttpStatus(responseStatus: HttpResponseStatus) = apply {
  statusCode = responseStatus.code()
  statusMessage = responseStatus.reasonPhrase()
}

/**
 * Sets the body of the response if it's not empty.
 * @param body The body (default: empty)
 */
private fun HttpServerResponse.endResponse(body: String? = null) {
  body?.let { this.end(it) } ?: this.end()
}
