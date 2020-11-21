package it.unibo.telestroke.session.models.session

import it.unibo.telestroke.session.models.template.Template
import java.util.*

data class Session(val id: String,
                   val specialist: String,
                   val operator: String,
                   val startDate: Date,
                   var endDate: Date?,
                   val template: String,
                   val phases: List<SessionPhase>) {

  constructor(specialist: String, operator: String, template: Template) :
    this("", specialist, operator, Date(), null, template.id, template.phases.map { SessionPhase(it.checklist, listOf(), listOf()) }.toList())
}
