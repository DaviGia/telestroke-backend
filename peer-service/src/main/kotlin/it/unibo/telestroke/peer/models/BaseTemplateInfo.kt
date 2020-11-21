package it.unibo.telestroke.peer.models

import com.fasterxml.jackson.annotation.JsonAlias

data class BaseTemplateInfo(@JsonAlias("_id") val id: String,
                            val name: String,
                            val description: String)
