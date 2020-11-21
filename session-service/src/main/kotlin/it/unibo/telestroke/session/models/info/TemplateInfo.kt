package it.unibo.telestroke.session.models.info

import com.fasterxml.jackson.annotation.JsonAlias
import it.unibo.telestroke.common.models.UserInfo
import java.util.*

data class TemplateInfo(@JsonAlias("_id") val id: String,
                        val name: String,
                        val description: String,
                        val author: UserInfo,
                        val createdAt: Date,
                        val changedAt: Date)
