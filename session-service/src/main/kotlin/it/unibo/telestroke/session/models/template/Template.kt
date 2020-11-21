package it.unibo.telestroke.session.models.template

import java.util.*

data class Template(val id: String,
                    val name: String,
                    val description: String,
                    val author: String,
                    val createdAt: Date,
                    val changedAt: Date,
                    val phases: List<TemplatePhase>)
