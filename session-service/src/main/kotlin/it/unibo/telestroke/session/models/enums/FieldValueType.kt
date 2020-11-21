package it.unibo.telestroke.session.models.enums

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The field value type.
 */
enum class FieldValueType {
  @JsonProperty("text")
  Text,
  @JsonProperty("multiline-text")
  MultilineText,
  @JsonProperty("number")
  Number,
  @JsonProperty("datetime")
  Datetime
}
