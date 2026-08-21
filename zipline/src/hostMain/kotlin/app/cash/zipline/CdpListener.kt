package app.cash.zipline

/**
 * Receives outbound CDP (Chrome DevTools Protocol) messages from the JS engine and gets notified
 * when debugger runtime tasks are enqueued. Implemented by the CDP transport
 * (see app.cash.zipline.internal.cdp). Methods may be invoked from arbitrary threads.
 */
internal interface CdpListener {
  /** A CDP response or event (UTF-8 JSON) ready to be sent to the debugger frontend. */
  fun onMessage(json: String)

  /**
   * One or more runtime tasks were enqueued and need [JsEngine.cdpDrainTasks] to run on the JS
   * thread at the next opportunity.
   */
  fun onTasksEnqueued()
}
