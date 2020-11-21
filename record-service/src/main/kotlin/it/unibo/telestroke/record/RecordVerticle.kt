package it.unibo.telestroke.record

import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.file.*
import it.unibo.telestroke.common.BaseVerticle
import it.unibo.telestroke.common.extensions.asyncHandler
import it.unibo.telestroke.common.extensions.error
import it.unibo.telestroke.common.extensions.success
import java.nio.file.Paths
import java.util.*

/**
 * The record service.
 */
class RecordVerticle : BaseVerticle() {

  companion object {
    private val log: Logger = LoggerFactory.getLogger(RecordVerticle::class.java)

    private const val DEFAULT_DIRECTORY = "recordings"
  }

  override suspend fun start() {
    super.start()

    log.debug("Initializing recordings folder...")

    val fs = vertx.fileSystem()

      if (!fs.existsAwait(DEFAULT_DIRECTORY)) {
        fs.mkdirAwait(DEFAULT_DIRECTORY)
      }
  }

  override fun createRouter(baseUrl: String): Router {
    return getBaseRouter().apply {
      post("/$baseUrl/recording").asyncHandler { handleSaveRecording(it) }
    }
  }

  private suspend fun handleSaveRecording(context: RoutingContext) {
    log.debug("Received save recording request...")
    try {
      val fs = vertx.fileSystem()
      val uniqueId = UUID.randomUUID().toString()
      val filePath = Paths.get(DEFAULT_DIRECTORY, uniqueId)
      val file = fs.openAwait(filePath.toString(), openOptionsOf(create = true, write = true))
      file.writeAwait(context.body)
      file.closeAwait()
      context.response().success()
      log.debug("Successfully saved recording as: $uniqueId")
    } catch (e: Throwable) {
      log.error("Unable to save recording", e)
      context.response().error()
    }
  }
}
