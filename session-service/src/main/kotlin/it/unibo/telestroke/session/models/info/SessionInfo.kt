package it.unibo.telestroke.session.models.info

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import it.unibo.telestroke.common.models.UserInfo
import java.util.*

@JsonIgnoreProperties("phases", "checklists", "authors")
data class SessionInfo(@JsonAlias("_id") val id: String,
                       val specialist: UserInfo,
                       val operator: UserInfo,
                       val startDate: Date,
                       val endDate: Date?,
                       val template: TemplateInfo)
