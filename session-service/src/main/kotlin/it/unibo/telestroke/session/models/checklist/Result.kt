package it.unibo.telestroke.session.models.checklist

import it.unibo.telestroke.session.models.enums.StrategyType

data class Result(val id: String,
                  val name: String,
                  val description: String,
                  val strategy: StrategyType,
                  val targetField: String,
                  val displayFormat: String)
