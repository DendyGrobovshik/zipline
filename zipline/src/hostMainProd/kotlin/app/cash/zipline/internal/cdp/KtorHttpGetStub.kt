package app.cash.zipline.internal.cdp

/** Prod builds ship without the Ktor HTTP client (used only by the CDP debug server). */
internal suspend fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
  throw UnsupportedOperationException(
    "CDP debugging requires the full engine (build with -PhermesProd=false)",
  )
}
