package it.unibo.telestroke.session.models.info

import com.fasterxml.jackson.annotation.JsonAlias
import it.unibo.telestroke.session.models.checklist.Step

data class StepInfo(@JsonAlias("_id") val id: String,
                    val name: String,
                    val description: String) {

  constructor(step: Step): this(step.id, step.name, step.description)
}
