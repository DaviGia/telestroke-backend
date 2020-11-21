package it.unibo.telestroke.peer.models

import com.fasterxml.jackson.annotation.JsonAlias
import it.unibo.telestroke.common.models.UserInfo
import java.util.*

data class BaseSessionInfo(@JsonAlias("_id") val id: String,
                           val specialist: UserInfo,
                           val startDate: Date,
                           val template: BaseTemplateInfo)
