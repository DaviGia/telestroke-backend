package it.unibo.telestroke.session.models.info

import it.unibo.telestroke.session.models.enums.FieldType
import it.unibo.telestroke.session.models.enums.FieldValueType

class ActionInfo(val value: String,
                 val type: FieldType,
                 val valueType: FieldValueType,
                 val step: StepInfo,
                 val field: FieldInfo)
