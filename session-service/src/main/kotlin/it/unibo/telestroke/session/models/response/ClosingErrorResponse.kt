package it.unibo.telestroke.session.models.response

import it.unibo.telestroke.session.models.utils.PhaseErrors

data class ClosingErrorResponse(val count: Int, val errors: List<PhaseErrors>)
