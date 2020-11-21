package it.unibo.telestroke.session.models.info

import it.unibo.telestroke.session.models.checklist.Field

data class FieldInfo(val id: String,
                     val name: String,
                     val description: String) {

  constructor(step: Field): this(step.id, step.name, step.description)
}
