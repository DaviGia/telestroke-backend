package it.unibo.telestroke.session.models.info

import com.fasterxml.jackson.annotation.JsonAlias
import it.unibo.telestroke.common.models.UserInfo
import java.util.*

data class CompleteSessionInfo(@JsonAlias("_id") val id: String,
                               val specialist: UserInfo,
                               val operator: UserInfo,
                               val startDate: Date,
                               val endDate: Date?,
                               val template: TemplateInfo,
                               val phases: List<PhaseInfo>) {

  constructor(sessionInfo: SessionInfo, phases: List<PhaseInfo>) :
    this(sessionInfo.id, sessionInfo.specialist, sessionInfo.operator,
      sessionInfo.startDate, sessionInfo.endDate, sessionInfo.template, phases)
}
