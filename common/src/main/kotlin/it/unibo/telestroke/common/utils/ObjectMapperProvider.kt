package it.unibo.telestroke.common.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Jackson object mapper provider.
 */
object ObjectMapperProvider {
  val mapper: ObjectMapper = jacksonObjectMapper()
    .findAndRegisterModules()
}
