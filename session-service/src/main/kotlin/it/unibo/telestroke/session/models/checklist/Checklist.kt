package it.unibo.telestroke.session.models.checklist

import java.util.*

data class Checklist(val id: String,
                     val name: String,
                     val description: String,
                     val author: String,
                     val createdAt: Date,
                     val changedAt: Date,
                     val steps: List<Step>,
                     val results: List<Result>) {

  /**
   * Retrieves all target fields for each step for the specified result id.
   * @param resultId The result id
   * @return The map of step id and target fields in the step
   */
  fun getTargetFieldsPerStep(resultId: String) : Map<String, List<Field>> {

    val resultTargetField = results.find { it.id == resultId }?.targetField ?:
      throw IllegalArgumentException("Unable to get result target fields because the result id do not match any checklist result")

    return steps.map { Pair(it.id, it.fields.filter { i -> i.references.contains(resultTargetField) }) }.toMap()
  }
}
