package it.unibo.telestroke.common.mongo.models

/**
 * The page response object.
 * @param page The page index (zero based)
 * @param limit The limit of items per page
 * @param total The total number of items
 * @param data The list of items.
 */
class PageResponse<T>(val page: Int, val limit: Int, val total: Long, val data: List<T>)
