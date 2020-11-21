package it.unibo.telestroke.session.models.checklist

import it.unibo.telestroke.session.models.enums.FieldType
import it.unibo.telestroke.session.models.session.Action

data class Field(val id: String,
                 val name: String,
                 val description: String,
                 val order: Int,
                 val type: FieldType = FieldType.Value,
                 val optional: Boolean = false,
                 val constraints: FieldConstraints = FieldConstraints(),
                 val values: List<FieldValue>,
                 val references: List<String>) {

  /**
   * Validates an action.
   * @param action The action to validate.
   * @return True, if the action is valid; otherwise false.
   */
  fun validateAction(action: Action): Boolean {

    if (action.field != this.id) throw IllegalArgumentException("Cannot validate action against step field if the target field is different")

    return when(this.type) {
      FieldType.Value -> this.constraints.validate(action.value, optional)
      FieldType.Select -> this.values.any { i -> i.value == action.value }
    }
  }
}
