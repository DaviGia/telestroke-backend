package it.unibo.telestroke.session.models.utils

data class PhaseErrors(val checklistId: String,
                       val missing: MutableMap<String, MutableList<String>> = mutableMapOf(),
                       val invalid: MutableMap<String, MutableList<String>> = mutableMapOf())
