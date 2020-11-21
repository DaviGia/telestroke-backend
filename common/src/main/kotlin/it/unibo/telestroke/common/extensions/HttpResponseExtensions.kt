package it.unibo.telestroke.common.extensions

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.client.HttpResponse

/**
 * Determines whether an HttpResponse is succeeded or not.
 */
fun HttpResponse<out Any>.success(): Boolean {
    return this.statusCode() < 300
}

/**
 * Determines whether an HttpResponse has body content or not.
 */
fun HttpResponse<out Any>.hasContent(): Boolean {
    return this.statusCode() != HttpResponseStatus.NO_CONTENT.code() && this.success() && this.body() != null
}
