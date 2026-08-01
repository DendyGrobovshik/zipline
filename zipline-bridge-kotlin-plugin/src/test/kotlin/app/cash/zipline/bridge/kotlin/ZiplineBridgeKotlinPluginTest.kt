/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.bridge.kotlin

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Test

/** Confirm the bridge compiler plugin generates C bridge files for @WithJS2HostBridge classes. */
@OptIn(ExperimentalCompilerApi::class)
class ZiplineBridgeKotlinPluginTest {

  @Test
  fun `cOutputDir option is accepted and compilation succeeds`() {
    val result = compileWithArgs(
      sourceFile = SourceFile.kotlin(
        "Test.kt",
        """
        package com.example

        class Greeting {
          fun sayHello(name: String): String = "Hello, ${'$'}name!"
        }
        """,
      ),
      args = listOf(
        "-P",
        "plugin:app.cash.zipline.bridge.kotlin:cOutputDir=/tmp/test-output",
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `compilation succeeds without cOutputDir option`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "Test.kt",
        """
        package com.example

        class Greeting {
          fun sayHello(name: String): String = "Hello, ${'$'}name!"
        }
        """,
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `annotated class generates bridge file with toJavaObject and bridge_init`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Bridged.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Bridged {
            val name: String = "test"
            val age: Int = 42
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Bridged.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Includes
      assertTrue(content.contains("#include \"quickjs/quickjs.h\""))
      assertTrue(content.contains("#include \"bridge_dispatch.h\""))

      // toJavaObject function signature (no JNIEXPORT, no jniBridge)
      assertTrue(content.contains("static jobject com_example_Bridged_toJavaObject("))
      assertTrue(content.contains("JNIEnv *env, JSContext *ctx, const JSValue *jsObj"))
      // No JNIEXPORT / jniBridge
      assertFalse(content.contains("JNIEXPORT"), "Should not contain JNIEXPORT")
      assertFalse(content.contains("jniBridge"), "Should not contain jniBridge")

      // Context and value casting
      assertFalse(content.contains("JSContext *ctx = (JSContext *)context;"),
        "Should not cast context from jlong")
      assertFalse(content.contains("const JSValue *jsObj = (const JSValue *)jsValue;"),
        "Should not cast jsValue from jlong")

      // Field extraction from JS object
      assertTrue(content.contains("JS_GetPropertyStr(ctx, *jsObj, \"name\")"))
      assertTrue(content.contains("JS_GetPropertyStr(ctx, *jsObj, \"age\")"))

      // String field conversion
      assertTrue(content.contains("JS_ToCString"))
      assertTrue(content.contains("NewStringUTF"))
      assertTrue(content.contains("JS_FreeCString"))

      // Int field conversion
      assertTrue(content.contains("JS_VALUE_GET_INT(js_age)"))

      // JS value cleanup
      assertTrue(content.contains("JS_FreeValue"))

      // Class and constructor lookup
      assertTrue(content.contains("FindClass"))
      assertTrue(content.contains("GetMethodID"))
      assertTrue(content.contains("\"()V\""))

      // Object construction
      assertTrue(content.contains("NewObject"))

      // Field setting
      assertTrue(content.contains("GetFieldID"))
      assertTrue(content.contains("(*env)->SetObjectField(env, result, _fld_name, java_name)"))
      assertTrue(content.contains("(*env)->SetIntField(env, result, _fld_age, java_age)"))

      // Return
      assertTrue(content.contains("return result;"))

      // bridge_init replaced by per-class __attribute__((constructor)) with addBridgeEntry
      assertTrue(content.contains("com_example_Bridged_bridge_register("))
      assertTrue(content.contains("addBridgeInit(com_example_Bridged_init)"))
      assertTrue(content.contains("addBridgeEntry(\"com.example.Bridged\""))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `per-class constructor registers in shared bridgeTable`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "TwoClasses.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Alpha {
            val x: Int = 1
          }

          @WithJS2HostBridge
          class Beta {
            val y: String = "hello"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val alphaFile = outputDir.resolve("com_example_Alpha.c").toFile()
      assertTrue(alphaFile.exists(), "Expected per-class C file for Alpha")
      val alphaContent = alphaFile.readText()
      assertTrue(alphaContent.contains("__attribute__((used, constructor))"))
      assertTrue(alphaContent.contains("addBridgeEntry(\"com.example.Alpha\""))

      val betaFile = outputDir.resolve("com_example_Beta.c").toFile()
      assertTrue(betaFile.exists(), "Expected per-class C file for Beta")
      val betaContent = betaFile.readText()
      assertTrue(betaContent.contains("__attribute__((used, constructor))"))
      assertTrue(betaContent.contains("addBridgeEntry(\"com.example.Beta\""))

      // No aggregate init file — per-class constructors handle registration.
      val initFile = outputDir.resolve("bridge_module_init.c").toFile()
      assertFalse(initFile.exists(), "bridge_module_init.c should not be generated")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `no per-class files when no annotated classes`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Plain.kt",
          """
          package com.example

          class Plain {
            val name: String = "test"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFiles = outputDir.toFile().listFiles { f -> f.name.endsWith(".c") } ?: emptyArray()
      assertEquals(0, cFiles.size, "No .c bridge files when no annotated classes")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `derived class includes inherited fields from annotated parent`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Animal.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          open class Animal {
            val species: String = ""
          }

          @WithJS2HostBridge
          class Dog : Animal() {
            val breed: String = ""
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Dog.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Both inherited and own fields are extracted
      assertTrue(content.contains("JS_GetPropertyStr(ctx, *jsObj, \"species\")"),
        "Should extract inherited 'species' field")
      assertTrue(content.contains("JS_GetPropertyStr(ctx, *jsObj, \"breed\")"),
        "Should extract own 'breed' field")

      // Both fields are set after construction
      assertTrue(content.contains("(*env)->SetObjectField(env, result, _fld_species, java_species)"),
        "Should set inherited 'species' field")
      assertTrue(content.contains("(*env)->SetObjectField(env, result, _fld_breed, java_breed)"),
        "Should set own 'breed' field")

      // toJavaObject function targets Dog, not Animal
      assertTrue(content.contains("com_example_Dog_toJavaObject"),
        "toJavaObject should target com.example.Dog")
      assertTrue(content.contains("com_example_Dog_bridge_register"),
        "constructor should target com.example.Dog")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `object fields use bridge_dispatch pattern`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Containing.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Nested {
            val value: Int = 1
          }

          @WithJS2HostBridge
          class Container {
            val label: String = ""
            val child: Nested = Nested()
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Container.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // String field still extracted directly
      assertTrue(content.contains("JS_GetPropertyStr(ctx, *jsObj, \"label\")"))
      assertTrue(content.contains("JS_ToCString"))

      // Nested object field uses bridge_dispatch property lookup
      assertTrue(content.contains("JS_GetPropertyStr(ctx, *jsObj, \"child\")"))
      assertTrue(content.contains("JS_GetPropertyStr(ctx, js_child, \"bridge_dispatch\")"))

      // TODO comment for pointer compression future
      assertTrue(content.contains("TODO: when pointer compression lands, use JS_VALUE_GET_INT"))

      // Dispatch pointer read via float64
      assertTrue(content.contains(
        "JniBridgeDispatch *disp_child = (JniBridgeDispatch *)(intptr_t)JS_VALUE_GET_FLOAT64(disp_val_child);"))

      // Dispatch call
      assertTrue(content.contains("disp_child->toJavaObject(env, ctx, &js_child)"))

      // Cleanup
      assertTrue(content.contains("JS_FreeValue(ctx, disp_val_child)"))

      // Object field uses SetObjectField with proper descriptor
      assertTrue(content.contains("(*env)->SetObjectField(env, result, _fld_child, java_child)"))
      assertTrue(content.contains("\"Lcom/example/Nested;\""),
        "Should use proper JNI field descriptor for Nested type")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `primitive array field generates typed JNI array code`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Scores.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Report {
            val scores: IntArray = intArrayOf(1, 2, 3)
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Report.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // JS array check
      assertTrue(content.contains("JS_IsArray(ctx, js_scores)"))

      // Typed JNI array creation
      assertTrue(content.contains("NewIntArray"))
      assertTrue(content.contains("GetIntArrayElements"))
      assertTrue(content.contains("ReleaseIntArrayElements"))
      assertTrue(content.contains("jintArray arr_scores"))
      assertTrue(content.contains("jint *elems_scores"))

      // Loop with element extraction
      assertTrue(content.contains("for (jint i = 0; i < len_js_scores; i++)"))
      assertTrue(content.contains("JS_GetPropertyUint32(ctx, js_scores, i)"))
      assertTrue(content.contains("JS_VALUE_GET_INT(elem)"))
      assertTrue(content.contains("JS_FreeValue(ctx, elem)"))

      // JNI field descriptor
      assertTrue(content.contains("[I"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `string array field generates NewObjectArray with string elements`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Names.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class NameList {
            val names: Array<String> = arrayOf("alice", "bob")
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_NameList.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // JS array check
      assertTrue(content.contains("JS_IsArray(ctx, js_names)"))

      // String array creation
      assertTrue(content.contains("FindClass(env, \"java/lang/String\")"))
      assertTrue(content.contains("NewObjectArray(env, len_js_names, strClass_names, NULL)"))

      // String element conversion in loop
      assertTrue(content.contains("JS_ToCString(ctx, elem)"))
      assertTrue(content.contains("NewStringUTF(env, str)"))
      assertTrue(content.contains("JS_FreeCString(ctx, str)"))
      assertTrue(content.contains("SetObjectArrayElement"))
      assertTrue(content.contains("DeleteLocalRef"))

      // JNI field descriptor for object array
      assertTrue(content.contains("[Ljava/lang/Object;"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `array of bridge objects uses dispatch per element`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Items.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Item {
            val x: Int = 0
          }

          @WithJS2HostBridge
          class ItemList {
            val items: Array<Item> = arrayOf(Item())
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_ItemList.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // General array path (since Item is not a known type like String or primitive)
      assertTrue(content.contains("JS_IsArray(ctx, js_items)"))

      // Pre-looked-up boxed classes (general path)
      assertTrue(content.contains("FindClass(env, \"java/lang/Integer\")"))

      // Per-element runtime dispatch
      assertTrue(content.contains("JS_VALUE_GET_NORM_TAG(elem)"))
      assertTrue(content.contains("case JS_TAG_INT:"))
      assertTrue(content.contains("case JS_TAG_OBJECT:"))

      // bridge_dispatch in the object branch
      assertTrue(content.contains("JS_GetPropertyStr(ctx, elem, \"bridge_dispatch\")"))
      assertTrue(content.contains("TODO: when pointer compression lands, use JS_VALUE_GET_INT"))
      assertTrue(content.contains("JniBridgeDispatch *disp ="))

      // NewObjectArray
      assertTrue(content.contains("NewObjectArray"))
      assertTrue(content.contains("SetObjectArrayElement"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `array of any generates full runtime dispatch`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Mixed.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class MixedBag {
            val values: Array<Any> = arrayOf(1, "hello", true)
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_MixedBag.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // General array dispatch
      assertTrue(content.contains("JS_IsArray(ctx, js_values)"))

      // All tag branches present
      assertTrue(content.contains("case JS_TAG_INT:"))
      assertTrue(content.contains("case JS_TAG_FLOAT64:"))
      assertTrue(content.contains("case JS_TAG_BOOL:"))
      assertTrue(content.contains("case JS_TAG_STRING:"))
      assertTrue(content.contains("case JS_TAG_OBJECT:"))

      // Pre-looked-up boxing classes
      assertTrue(content.contains("cls_values_Int"))
      assertTrue(content.contains("ctor_values_Int"))
      assertTrue(content.contains("cls_values_Double"))
      assertTrue(content.contains("cls_values_Boolean"))

      // Object branch uses bridge_dispatch
      assertTrue(content.contains("JS_GetPropertyStr(ctx, elem, \"bridge_dispatch\")"))
      assertTrue(content.contains("JS_IsUndefined(disp_val)"))

      // Cleanup
      assertTrue(content.contains("DeleteLocalRef"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable string field generates null check`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Nullable.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class OptString {
            val name: String? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptString.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // C type is jstring (same as non-null String)
      assertTrue(content.contains("jstring java_name;"))

      // Null check wrapping string extraction
      assertTrue(content.contains("JS_IsUndefined(js_name)"))
      assertTrue(content.contains("JS_IsNull(js_name)"))
      assertTrue(content.contains("JS_ToCString(ctx, js_name)"))
      assertTrue(content.contains("NewStringUTF(env, str_name)"))

      // Null branch sets to NULL
      assertTrue(content.contains("java_name = NULL;"))

      // JNI field descriptor unchanged
      assertTrue(content.contains("\"Ljava/lang/String;\""))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable int field generates boxing code`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableInt.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class OptInt {
            val age: Int? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptInt.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // C type is jobject (not jint) for nullable primitive
      assertTrue(content.contains("jobject java_age;"))

      // Pre-lookup of boxed class and constructor
      assertTrue(content.contains("FindClass(env, \"java/lang/Integer\")"))
      assertTrue(content.contains("GetMethodID(env, _boxed_age, \"<init>\", \"(I)V\")"))

      // Null check
      assertTrue(content.contains("JS_IsUndefined(js_age)"))
      assertTrue(content.contains("JS_IsNull(js_age)"))

      // Boxing via NewObject
      assertTrue(content.contains("NewObject(env, _boxed_age, _boxedCtor_age, (jint)JS_VALUE_GET_INT(js_age))"))

      // Null branch
      assertTrue(content.contains("java_age = NULL;"))

      // JNI field descriptor uses boxed type
      assertTrue(content.contains("\"Ljava/lang/Integer;\""))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable object field generates null check`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableObj.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Nested {
            val value: Int = 1
          }

          @WithJS2HostBridge
          class OptContainer {
            val label: String = ""
            val child: Nested? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptContainer.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Null check for child field
      assertTrue(content.contains("JS_IsUndefined(js_child)"))
      assertTrue(content.contains("JS_IsNull(js_child)"))

      // bridge_dispatch lookup inside null check
      assertTrue(content.contains("JS_GetPropertyStr(ctx, js_child, \"bridge_dispatch\")"))
      assertTrue(content.contains("disp_child->toJavaObject(env, ctx, &js_child)"))

      // Null branch
      assertTrue(content.contains("java_child = NULL;"))

      // Label (non-null string) does NOT get null check
      assertTrue(content.indexOf("JS_IsUndefined(js_label)") == -1,
        "Non-nullable label field should not have null check")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable array field generates null check`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableArray.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class OptArray {
            val scores: IntArray? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptArray.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Null check for array field
      assertTrue(content.contains("JS_IsUndefined(js_scores)"))
      assertTrue(content.contains("JS_IsNull(js_scores)"))

      // Array extraction inside null check
      assertTrue(content.contains("JS_IsArray(ctx, js_scores)"))
      assertTrue(content.contains("NewIntArray"))
      assertTrue(content.contains("GetIntArrayElements"))

      // Null branch
      assertTrue(content.contains("java_scores = NULL;"))

      // JNI field descriptor unchanged for nullable array
      assertTrue(content.contains("[I"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `string nullable array elements handle null per element`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableNames.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class NullableNames {
            val names: Array<String?> = arrayOf("alice", null, "bob")
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_NullableNames.c").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Per-element null check
      assertTrue(content.contains("JS_IsUndefined(elem)"))
      assertTrue(content.contains("JS_IsNull(elem)"))

      // String conversion inside null check
      assertTrue(content.contains("JS_ToCString(ctx, elem)"))
      assertTrue(content.contains("NewStringUTF(env, str)"))

      // java_elem default is NULL
      assertTrue(content.contains("jobject java_elem = NULL;"))

      // Still uses NewObjectArray and String class
      assertTrue(content.contains("FindClass(env, \"java/lang/String\")"))
      assertTrue(content.contains("NewObjectArray"))
      assertTrue(content.contains("SetObjectArrayElement"))
      assertTrue(content.contains("DeleteLocalRef"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `plugin compiles simple class successfully`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "Test.kt",
        """
        package com.example

        class Greeting {
          fun sayHello(name: String): String = "Hello, ${'$'}name!"
        }
        """,
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `plugin compiles interface with properties and functions`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "Service.kt",
        """
        package com.example

        interface Calculator {
          fun add(a: Int, b: Int): Int
          fun subtract(a: Int, b: Int): Int
        }

        class SimpleCalculator : Calculator {
          override fun add(a: Int, b: Int): Int = a + b
          override fun subtract(a: Int, b: Int): Int = a - b
        }
        """,
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }
}

@ExperimentalCompilerApi
fun compile(
  sourceFiles: List<SourceFile>,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return KotlinCompilation().apply {
    sources = sourceFiles
    compilerPluginRegistrars = listOf(plugin)
    inheritClassPath = true
  }.compile()
}

@ExperimentalCompilerApi
fun compile(
  sourceFile: SourceFile,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return compile(listOf(sourceFile), plugin)
}

@ExperimentalCompilerApi
fun compileWithArgs(
  sourceFiles: List<SourceFile>,
  args: List<String>,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return KotlinCompilation().apply {
    sources = sourceFiles
    compilerPluginRegistrars = listOf(plugin)
    commandLineProcessors = listOf(ZiplineBridgeCommandLineProcessor())
    inheritClassPath = true
    kotlincArguments = args
  }.compile()
}

@ExperimentalCompilerApi
fun compileWithArgs(
  sourceFile: SourceFile,
  args: List<String>,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return compileWithArgs(listOf(sourceFile), args, plugin)
}

/**
 * Compiles with a custom [CompilerPluginRegistrar] that directly injects [cOutputDir]
 * into the IR extension, bypassing the CLI argument / [CommandLineProcessor] path.
 *
 * This is needed because [KotlinCompilation] with [compilerPluginRegistrars] does not
 * properly route [kotlincArguments] through [org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor].
 */
@ExperimentalCompilerApi
fun compileWithCOutputDir(
  sourceFile: SourceFile,
  cOutputDir: String,
): JvmCompilationResult {
  val customRegistrar = object : CompilerPluginRegistrar() {
    override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
      IrGenerationExtension.registerExtension(
        ZiplineBridgeIrGenerationExtension(cOutputDir),
      )
    }
  }
  return KotlinCompilation().apply {
    sources = listOf(sourceFile)
    compilerPluginRegistrars = listOf(customRegistrar)
    inheritClassPath = true
  }.compile()
}
