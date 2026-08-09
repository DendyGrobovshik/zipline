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

/**
 * Test class for arrays and lists of primitives
 */
@WithJS2HostBridge
data class BridgedArray(
  val intArray: IntArray,
  val stringArray: Array<String>,
  val booleanArray: BooleanArray,
  val doubleArray: DoubleArray,
  val primitiveList: List<Int>,
  val stringList: List<String>,
)

/**
 * Test class for nested arrays/lists
 */
@WithJS2HostBridge
data class BridgedNestedStructure(
  val nestedArray: Array<Array<Int>>,
  val nestedList: List<List<String>>,
  val mixedStructure: Array<List<IntArray>>,
)

/**
 * Test class for empty collections
 */
@WithJS2HostBridge
data class BridgedEmptyCollections(
  val emptyArray: IntArray,
  val emptyList: List<String>,
  val emptyMap: Map<String, Int>,
)

/**
 * Test class for inheritance scenarios including overrides
 */
@WithJS2HostBridge
open class BridgedBaseClass {
  open val baseProperty: String = "base"
  val baseField: Int = 10
}

@WithJS2HostBridge
open class BridgedInheritanceChild : BridgedBaseClass() {
  val childField: Int = 1
}

@WithJS2HostBridge
class BridgedDeepInheritance : BridgedInheritanceChild() {
  val deepField: Double = 3.14
}

/**
 * Test class for property overrides with backing fields
 */
@WithJS2HostBridge
open class BridgedOverrideBase {
  open val overriddenProperty: String = "base"
  open var overriddenVar: Int = 5
  val regularField: Boolean = true
}

@WithJS2HostBridge  
class BridgedOverrideChild : BridgedOverrideBase() {
  override val overriddenProperty: String = "overridden"
  override var overriddenVar: Int = 10
  val childField: String = "child"
}

/**
 * Test class for interfaces and interface implementation
 */
@WithJS2HostBridge
interface BridgedInterface {
  val interfaceProperty: String
  fun interfaceMethod(): Int
}

@WithJS2HostBridge
class BridgedInterfaceImplementation : BridgedInterface {
  override val interfaceProperty: String = "implemented"
  override fun interfaceMethod(): Int = 42
}

/**
 * Test class for generic types with type parameters
 */
@WithJS2HostBridge
data class BridgedGenericClass<T>(
  val value: T,
  val list: List<T>
)

/**
 * Test class for multiple type parameters
 */
@WithJS2HostBridge
data class BridgedMultiGenericClass<T, U>(
  val first: T,
  val second: U,
  val both: Map<T, U>
)

/**
 * Test class for bounded generics (where T : SomeClass)
 */
@WithJS2HostBridge
open class BridgedBoundedGenericBase {
  open val baseProperty: String = "base"
}

@WithJS2HostBridge
data class BridgedBoundedGenericClass<T : BridgedBoundedGenericBase>(
  val value: T,
  val list: List<T>
)

/**
 * Test class for nested generics
 */
@WithJS2HostBridge
data class BridgedNestedGeneric(
  val mapOfLists: Map<String, List<Int>>,
  val listOfMaps: List<Map<String, Int>>,
  val complexNested: Map<String, List<Map<Int, String>>>
)

/** Canonical values used both by the guest providers and the host assertions. */

object BridgedTestValues {
  val data = BridgedData(id = 7, name = "seven", active = true, ratio = 1.5)
  val inline = BridgedInline(raw = 42)
  val inlineHolder = BridgedInlineHolder(inlineValue = inline)
  val listHolder = BridgedListHolder(items = listOf(1, 2, 3))
  val nested = BridgedNested(outer = data)
  val nullableNull = BridgedNullable(text = null, count = null)
  val nullableValue = BridgedNullable(text = "x", count = 1)
  
  // New test values for collections
  val array = BridgedArray(
    intArray = intArrayOf(1, 2, 3),
    stringArray = arrayOf("a", "b", "c"),
    booleanArray = booleanArrayOf(true, false),
    doubleArray = doubleArrayOf(1.0, 2.0, 3.0),
    primitiveList = listOf(1, 2, 3),
    stringList = listOf("a", "b", "c")
  )
  
  val nestedStructure = BridgedNestedStructure(
    nestedArray = arrayOf(arrayOf(1, 2), arrayOf(3, 4)),
    nestedList = listOf(listOf("a", "b"), listOf("c", "d")),
    mixedStructure = arrayOf(listOf(intArrayOf(1, 2), intArrayOf(3, 4)))
  )
  
  val emptyCollections = BridgedEmptyCollections(
    emptyArray = intArrayOf(),
    emptyList = listOf(),
    emptyMap = mapOf()
  )
  
  // New test values for inheritance
  val baseClass = BridgedBaseClass()
  val inheritanceChild = BridgedInheritanceChild()
  val deepInheritance = BridgedDeepInheritance()
  val overrideBase = BridgedOverrideBase()
  val overrideChild = BridgedOverrideChild()
  val interfaceImpl = BridgedInterfaceImplementation()
  
  // New test values for generics
  val genericInt = BridgedGenericClass(42, listOf(1, 2, 3))
  val genericString = BridgedGenericClass("hello", listOf("a", "b", "c"))
  val multiGeneric = BridgedMultiGenericClass("key", 42, mapOf("key" to 42))
  val boundedGeneric = BridgedBoundedGenericClass(BridgedBoundedGenericBase(), listOf(BridgedBoundedGenericBase()))
  val nestedGeneric = BridgedNestedGeneric(
    mapOfLists = mapOf("list1" to listOf(1, 2, 3)),
    listOfMaps = listOf(mapOf("a" to 1, "b" to 2)),
    complexNested = mapOf("outer" to listOf(mapOf(1 to "one", 2 to "two")))
  )
}