package it.unibo.telestroke.session.models.info

data class PhaseInfo(val checklist: ChecklistInfo,
                     val actions: List<ActionInfo>,
                     val results: List<ResultInfo>)
