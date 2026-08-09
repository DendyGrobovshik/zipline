/*
 * End-to-end tests for the @WithJS2HostBridge bridge compiler plugin.
 *
 * The same tests run on both backends:
 * - jvmTest (JVM/JNI): evaluateForBridge goes through Context::toJavaObject, which dispatches
 *   to the generated C converters in libbridgetests.dylib.
 * - nativeTest (Kotlin/Native): evaluateForBridge evaluates the script and runs the raw
 *   JS value through bridgeForAny, which dispatches to the generated X_toKotlin converters.
 *
 * The JS guest (jsMain) constructs each value; constructing it triggers the bridge registration
 * injected by the compiler plugin, so the host can dispatch the result back to a Kotlin object.
 */
package app.cash.zipline.bridge.test

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

/** Host backend: loads the guest bytecode and evaluates JS through the bridge dispatcher. */
expect class TestHost() {
  fun loadGuest()
  fun evaluateOne(script: String): Any?
  fun close()
}

/** Backend base64 decoder for the embedded guest bytecode. */
expect fun decodeGuestBase64(encoded: String): ByteArray

/**
 * Decodes an embedded guest module (a ZiplineFile container) into raw QuickJS bytecode.
 * The container format is parsed with the canonical [app.cash.zipline.loader.ZiplineFile] reader.
 */
internal fun decodeGuestModule(encoded: String): ByteArray {
  val container = app.cash.zipline.loader.ZiplineFile.read(
    okio.Buffer().write(decodeGuestBase64(encoded)),
  )
  return container.quickjsBytecode.toByteArray()
}

class BridgeEndToEndTest {
  private lateinit var host: TestHost

  @BeforeTest
  fun setUp() {
    host = TestHost()
    host.loadGuest()
  }

  @AfterTest
  fun tearDown() {
    host.close()
  }

  private fun evalOne(provider: String): Any? {
    return host.evaluateOne("require('$GUEST_MODULE').app.cash.zipline.bridge.test.$provider()")
  }

  @Test
  fun bridgedData() {
    assertEquals(BridgedTestValues.data, evalOne("provideBridgedData"))
  }

  @Test
  fun bridgedInlineHolder() {
    assertEquals(BridgedTestValues.inlineHolder, evalOne("provideBridgedInlineHolder"))
  }

  @Test
  fun bridgedListHolder() {
    assertEquals(BridgedTestValues.listHolder, evalOne("provideBridgedListHolder"))
  }

  @Test
  fun bridgedNested() {
    assertEquals(BridgedTestValues.nested, evalOne("provideBridgedNested"))
  }

  @Test
  fun bridgedNullableNull() {
    assertEquals(BridgedTestValues.nullableNull, evalOne("provideBridgedNullableNull"))
  }

  @Test
  fun bridgedNullableValue() {
    assertEquals(BridgedTestValues.nullableValue, evalOne("provideBridgedNullableValue"))
  }

  // New tests for collections
  @Test
  fun bridgedArray() {
    assertEquals(BridgedTestValues.array, evalOne("provideBridgedArray"))
  }


  @Test
  fun bridgedNestedStructure() {
    assertEquals(BridgedTestValues.nestedStructure, evalOne("provideBridgedNestedStructure"))
  }

  @Test
  fun bridgedEmptyCollections() {
    assertEquals(BridgedTestValues.emptyCollections, evalOne("provideBridgedEmptyCollections"))
  }

  // New tests for inheritance
  @Test
  fun bridgedBaseClass() {
    assertEquals(BridgedTestValues.baseClass, evalOne("provideBridgedBaseClass"))
  }

  @Test
  fun bridgedInheritanceChild() {
    assertEquals(BridgedTestValues.inheritanceChild, evalOne("provideBridgedInheritanceChild"))
  }

  @Test
  fun bridgedDeepInheritance() {
    assertEquals(BridgedTestValues.deepInheritance, evalOne("provideBridgedDeepInheritance"))
  }

  @Test
  fun bridgedOverrideBase() {
    assertEquals(BridgedTestValues.overrideBase, evalOne("provideBridgedOverrideBase"))
  }

  @Test
  fun bridgedOverrideChild() {
    assertEquals(BridgedTestValues.overrideChild, evalOne("provideBridgedOverrideChild"))
  }

  @Test
  fun bridgedInterfaceImplementation() {
    assertEquals(BridgedTestValues.interfaceImpl, evalOne("provideBridgedInterfaceImplementation"))
  }

  // New tests for generics
  @Test
  fun bridgedGenericClassInt() {
    assertEquals(BridgedTestValues.genericInt, evalOne("provideBridgedGenericClassInt"))
  }

  @Test
  fun bridgedGenericClassString() {
    assertEquals(BridgedTestValues.genericString, evalOne("provideBridgedGenericClassString"))
  }

  @Test
  fun bridgedMultiGenericClass() {
    assertEquals(BridgedTestValues.multiGeneric, evalOne("provideBridgedMultiGenericClass"))
  }

  @Test
  fun bridgedBoundedGenericClass() {
    assertEquals(BridgedTestValues.boundedGeneric, evalOne("provideBridgedBoundedGenericClass"))
  }

  @Test
  fun bridgedNestedGeneric() {
    assertEquals(BridgedTestValues.nestedGeneric, evalOne("provideBridgedNestedGeneric"))
  }

}