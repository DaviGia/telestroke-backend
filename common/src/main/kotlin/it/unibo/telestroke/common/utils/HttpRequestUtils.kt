package it.unibo.telestroke.common.utils

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.HttpResponse

/**
 * Http request utils.
 */
object HttpRequestUtils {

  /**
   * Copies the query parameters from one request to another.
   * @param original The original routing context
   * @param target The target request
   */
  fun copyQueryParams(original: RoutingContext, target: HttpRequest<Buffer>) {
    original.queryParams().forEach {
      target.addQueryParam(it.key, it.value)
    }
  }

  /**
   * Copies the headers from one request to another.
   * @param original The original request
   * @param target The target request
   */
  fun copyHeaders(original: HttpServerRequest, target: HttpRequest<Buffer>) {
    original.headers().forEach {
      target.putHeader(it.key, it.value)
    }
  }

  /**
   * Copies the headers from one request to another.
   * @param original The original request
   * @param target The target request
   */
  fun copyHeaders(original: HttpResponse<Buffer>, target: HttpServerResponse) {
    original.headers().forEach {
      target.putHeader(it.key, it.value)
    }
  }
}
