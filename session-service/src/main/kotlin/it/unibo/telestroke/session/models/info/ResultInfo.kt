package it.unibo.telestroke.session.models.info

import it.unibo.telestroke.session.models.enums.StrategyType
import it.unibo.telestroke.session.models.checklist.Result

data class ResultInfo(val id: String,
                      val name: String,
                      val description: String,
                      val strategy: StrategyType,
                      val displayFormat: String,
                      val referencedFields: List<FieldInfo>,
                      val value: String) {

  constructor(result: Result, fields: List<FieldInfo>, value: String) :
    this(result.id, result.name, result.description, result.strategy, result.displayFormat, fields, value)

}
