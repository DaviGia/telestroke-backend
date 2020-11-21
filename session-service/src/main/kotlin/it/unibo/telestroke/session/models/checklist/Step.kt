package it.unibo.telestroke.session.models.checklist


data class Step(val id: String,
                val name: String,
                val description: String,
                val order: Int,
                val fields: List<Field>)
