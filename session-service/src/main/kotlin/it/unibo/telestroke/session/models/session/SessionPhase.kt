package it.unibo.telestroke.session.models.session

import it.unibo.telestroke.session.models.checklist.Checklist
import it.unibo.telestroke.session.models.checklist.Field
import it.unibo.telestroke.session.models.enums.FieldType
import it.unibo.telestroke.session.models.enums.StrategyType
import it.unibo.telestroke.session.models.utils.PhaseErrors

data class SessionPhase(val checklist: String,
                        val actions: List<Action>,
                        val results: List<PhaseResult>) {

  /**
   * Checks for not compiled steps or invalid values.
   */
  fun validate(templatePhase: Checklist): PhaseErrors {
    val phaseErrors = PhaseErrors(checklist, mutableMapOf(), mutableMapOf())

    for (step in templatePhase.steps) {
      val actionSteps = actions.filter { it.step == step.id }

      val (present, missing) = step.fields
        .map { Pair(it, actionSteps.find { a -> a.field == it.id }) }
        .filter { !it.first.optional }
        .partition { it.second != null }

      val incorrect = present.filter { !validateValue(it.first, it.second?.value ?: "") }

      if (missing.isNotEmpty()) {
        phaseErrors.missing[step.id] = missing.sortedBy { it.first.order }.map { it.first.id }.toMutableList()
      }

      if (incorrect.isNotEmpty()) {
        phaseErrors.invalid[step.id] = incorrect.sortedBy { it.first.order }.map { it.first.id }.toMutableList()
      }
    }

    return phaseErrors
  }

  /**
   * Validates a value against the constraints and field type.
   */
  private fun validateValue(field: Field, value: String): Boolean {

    if (value.isEmpty()) return false

    return when (field.type) {
      FieldType.Value -> {
        field.constraints.validate(value)
      }
      FieldType.Select -> {
        field.values.any { it.value == value }
      }
    }
  }

  /**
   * Computes the results of this phase.
   * @return The list of result for each phase.
   */
  fun computeResults(templatePhase: Checklist) : List<PhaseResult> {

    if (templatePhase.id != checklist) throw IllegalArgumentException("Unable to compute result if the input checklist is not")

    return templatePhase.results.map { result ->

      val strategy = result.strategy
      val fieldsPerStep = templatePhase.getTargetFieldsPerStep(result.id) //stepId, list<field>
      val matchedActions = actions.filter { a -> fieldsPerStep.any { it.key == a.step && it.value.any { i -> i.id == a.field } } }
      val resultValue = aggregateActionValues(strategy, matchedActions)

      PhaseResult(result.id, resultValue)
    }
  }

  /**
   * Aggregates all action values with the selected strategy type.
   * @return The result of the values aggregation
   */
  private fun aggregateActionValues(strategy: StrategyType, actions: List<Action>): String {
    when(strategy) {
      StrategyType.Sum -> {
        return actions.filter { it.value.isNotEmpty() }.map { it.value.toInt() }.sum().toString()
      }
      else -> throw IllegalArgumentException("Unable to aggregate action values because the strategy type is unknown")
    }
  }
}
