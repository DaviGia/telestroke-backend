package it.unibo.telestroke.session.models.info

import com.fasterxml.jackson.annotation.JsonAlias
import it.unibo.telestroke.common.models.UserInfo
import it.unibo.telestroke.session.models.checklist.Checklist
import java.util.*

data class ChecklistInfo(@JsonAlias("_id") val id: String,
                         val name: String,
                         val description: String,
                         val author: UserInfo,
                         val createdAt: Date,
                         val changedAt: Date) {

  constructor(checklist: Checklist, author: UserInfo) :
    this(checklist.id, checklist.name, checklist.description, author, checklist.createdAt, checklist.changedAt)
}
