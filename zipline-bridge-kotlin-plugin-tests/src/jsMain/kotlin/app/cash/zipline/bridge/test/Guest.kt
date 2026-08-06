/*
 * JS guest: constructs @WithJS2HostBridge values on the JS side. Constructing each value
 * triggers the bridge registration injected by the compiler plugin, so the host can
 * dispatch the result back to a Kotlin object.
 */
package app.cash.zipline.bridge.test

@JsExport
fun provideBridgedData(): BridgedData = BridgedTestValues.data

@JsExport
fun provideBridgedInlineHolder(): BridgedInlineHolder = BridgedTestValues.inlineHolder

@JsExport
fun provideBridgedListHolder(): BridgedListHolder = BridgedTestValues.listHolder

@JsExport
fun provideBridgedNested(): BridgedNested = BridgedTestValues.nested

@JsExport
fun provideBridgedNullableNull(): BridgedNullable = BridgedTestValues.nullableNull

@JsExport
fun provideBridgedNullableValue(): BridgedNullable = BridgedTestValues.nullableValue
