/*
 * Test classes for the bridge compiler plugin end-to-end tests.
 *
 * Each class exercises a distinct generator branch:
 * - BridgedData: primitive + String fields
 * - BridgedInline: @JvmInline value class wrapper
 * - BridgedListHolder: List<Int> field (regression for the array_1 double-free)
 * - BridgedNested: object field dispatched through the nested bridge
 * - BridgedNullable: nullable String and Int fields
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

@WithJS2HostBridge
data class BridgedData(
  val id: Int,
  val name: String,
  val active: Boolean,
  val ratio: Double,
)

@WithJS2HostBridge
@JvmInline
value class BridgedInline(val raw: Int)

/**
 * Inline classes can only be bridged as fields (or list elements): Kotlin/JS inlines a
 * standalone value-class return value to the underlying primitive, which carries no
 * bridge_dispatch property.
 */
@WithJS2HostBridge
data class BridgedInlineHolder(val inlineValue: BridgedInline)

@WithJS2HostBridge
data class BridgedListHolder(val items: List<Int>)

@WithJS2HostBridge
data class BridgedNested(val outer: BridgedData)

@WithJS2HostBridge
data class BridgedNullable(val text: String?, val count: Int?)

/** Canonical values used both by the guest providers and the host assertions. */
object BridgedTestValues {
  val data = BridgedData(id = 7, name = "seven", active = true, ratio = 1.5)
  val inline = BridgedInline(raw = 42)
  val inlineHolder = BridgedInlineHolder(inlineValue = inline)
  val listHolder = BridgedListHolder(items = listOf(1, 2, 3))
  val nested = BridgedNested(outer = data)
  val nullableNull = BridgedNullable(text = null, count = null)
  val nullableValue = BridgedNullable(text = "x", count = 1)
}
