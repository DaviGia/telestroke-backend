package it.unibo.telestroke.common.extensions

import io.vertx.core.json.JsonObject

private const val MONGODB_ID_KEY = "_id"
private const val MONGODB_FLATTENED_ID_KEY = "id"

/**
 * Flattens the mongodb \"_id\" object into a single string property.
 * @param key The new key (default: id)
 */
fun JsonObject.flattenId(key: String = MONGODB_FLATTENED_ID_KEY): JsonObject = apply {

  val objId = when(this.getValue(MONGODB_ID_KEY)) {
    is String -> this.getString(MONGODB_ID_KEY)
    is JsonObject -> this.getJsonObject(MONGODB_ID_KEY).getString("\$oid")
    else -> null
  }

  objId?.let {
    purgeId()
    put(key, it)
  }
}

/**
 * Flattens the mongodb \"_id\" object into a single string property inside all objects in the collection.
 * @param key The new key (default: id)
 */
fun List<JsonObject>.flattenId(key: String = MONGODB_FLATTENED_ID_KEY): List<JsonObject> = apply {
  this.forEach { it.flattenId(key) }
}

/**
 * Removes the id property inside the json object if it's present.
 * @param key The identifier key (default: _id)
 */
fun JsonObject.purgeId(key: String = MONGODB_ID_KEY): JsonObject = apply {
    this.remove(key)
}

/**
 * Removes the id property inside all json objects if it's present.
 * @param key The identifier key (default: _id)
 */
fun List<JsonObject>.purgeIds(key: String = MONGODB_ID_KEY): List<JsonObject> = apply {
    this.forEach { it.purgeId(key) }
}
