package it.unibo.telestroke.session.models.enums

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The field type.
 */
enum class FieldType {
  @JsonProperty("value")
  Value,
  @JsonProperty("select")
  Select
}
