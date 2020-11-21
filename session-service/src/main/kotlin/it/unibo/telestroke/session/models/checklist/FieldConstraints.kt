package it.unibo.telestroke.session.models.checklist

import it.unibo.telestroke.session.models.enums.FieldValueType
import java.util.*

data class FieldConstraints(val valueType: FieldValueType = FieldValueType.Text,
                            val min: Double? = null,
                            val max: Double? = null) {

  /**
   * Validate the input value against the defined constraints.
   * @param value The value to validate
   * @param optional Whether optional values are allowed or not
   * @return True, if the value is valid; otherwise false.
   */
  fun validate(value: String, optional: Boolean = false): Boolean {

    //empty
    if (value.isEmpty() && !optional) return false

    return try {
      when (valueType) {
        FieldValueType.Text,
        FieldValueType.MultilineText -> checkRange(value.length.toDouble())
        FieldValueType.Number -> checkRange(value.toDouble())
        FieldValueType.Datetime -> {
          Date(value.toLong())
          true
        }
      }
    } catch (ignored: Exception) {
      false
    }
  }

  /**
   * Determines if the input value is within range or not.
   * @param value The value
   * @return True, if it's in range; otherwise false.
   */
  private fun checkRange(value: Double): Boolean {
    val lower = min ?: Double.MIN_VALUE
    val upper = max ?: Double.MAX_VALUE
    return (value >= lower) && (value <= upper)
  }
}
