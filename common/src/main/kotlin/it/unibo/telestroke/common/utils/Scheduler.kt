package it.unibo.telestroke.common.utils

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Scheduler
 */
object Scheduler {

  private val executor = Executors.newScheduledThreadPool(1)
  private var currentTask: ScheduledFuture<*>? = null

  /**
   * Starts the scheduling
   * @param schedulingTime The scheduling time
   * @param unit The time unit
   * @param operation The operation
   */
  fun start(schedulingTime: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, operation: () -> Unit) {
    currentTask = executor.scheduleAtFixedRate(Runnable(operation), 5000, schedulingTime, unit)
  }

  /**
   * Stops the current task. If no task is running this operations has no effect.
   */
  fun stop() {
    currentTask?.cancel(true)
  }

  /**
   * Disposes the scheduler
   */
  fun dispose() {
    stop()
    executor.shutdown()
    executor.awaitTermination(20, TimeUnit.SECONDS)
  }
}
