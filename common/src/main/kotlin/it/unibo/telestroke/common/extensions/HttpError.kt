package it.unibo.telestroke.common.extensions

/**
 * Http error.
 * @param statusCode The status code
 * @param statusMessage The status message
 */
class HttpError(val statusCode: Int, val statusMessage: String) : Error()
