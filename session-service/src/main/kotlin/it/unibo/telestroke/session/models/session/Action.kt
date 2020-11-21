package it.unibo.telestroke.session.models.session

import java.util.*

data class Action(val step: String,
                  val time: Date,
                  val field: String,
                  val value: String)
