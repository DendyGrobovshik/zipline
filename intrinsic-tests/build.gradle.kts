import org.jetbrains.kotlin.gradle.dsl.JsModuleKind
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
}

val quickjsSourceDir = file("native-test/hermes")

tasks.register<Exec>("buildQjs") {
    group = "build"
    description = "Build qjs executable from source"

    dependsOn(":zipline:buildHermesMacosStatic")
    workingDir = quickjsSourceDir
    commandLine("sh", "-c", "cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build")
}

tasks.register<Exec>("jsQjsTest") {
    group = "verification"
    description = "Run JS tests with qjs"

    dependsOn("jsBrowserDevelopmentWebpack")
    dependsOn("buildQjs")

    val project = project
    val mainDir = file("${project.layout.buildDirectory.get().asFile.path}/compileSync/js/main/developmentExecutable")
    val bundleOutputDir = file("${mainDir.path}/dist")
    val qjsBinary = file("${quickjsSourceDir}/qjs")
    // KGP downloads webpack into the root build/js dir; no local npm install needed.
    val webpackJs = rootProject.layout.buildDirectory.file("js/node_modules/webpack/bin/webpack.js").get().asFile

    workingDir = mainDir
    commandLine(
        "sh", "-c", """
        cp ${file("webpack-test.config.js").absolutePath} ${mainDir.absolutePath}/webpack.config.js
        mkdir -p dist
        node ${webpackJs.absolutePath} --config webpack.config.js
        ${qjsBinary.absolutePath} ${bundleOutputDir.absolutePath}/collection-bundle.js
    """
    )
}

// Run the qjs intrinsics suite as part of the standard jsTest lifecycle task.
// allTests runs ONLY the qjs intrinsics suite: the stock KGP test runners
// (Karma/browser, node, JVM, native) are not part of this module's test
// surface. afterEvaluate because KGP wires allTests' dependencies late.
afterEvaluate {
    tasks.named("allTests") {
        setDependsOn(listOf("jsQjsTest"))
    }
}

kotlin {
  jvm()

  js {
    browser {
      testTask {
        enabled = false
      }
    }
    nodejs {
      testTask {
        enabled = false
      }
    }
    binaries.executable()
    // TODO upstream this to ZiplinePlugin
    binaries.library()
    binaries.executable()
    compilerOptions {
      target.set("es2015")
      moduleKind.set(JsModuleKind.MODULE_UMD)
    }
  }

  linuxX64()
  macosX64()
  macosArm64()
  iosArm64()
  iosX64()
  iosSimulatorArm64()
  tvosArm64()
  tvosSimulatorArm64()
  tvosX64()

  applyDefaultHierarchyTemplate {
    common {
      group("nonJs") {
        withJvm()
        withNative()
      }
    }
  }

  sourceSets {
    all {
      languageSettings.optIn("kotlin.js.ExperimentalJsExport")
    }

    val commonMain by getting {
      dependencies {
        implementation(projects.zipline)
        implementation(projects.ziplineCryptography)
      }
    }

    val jsMain by getting {
      dependencies {
        implementation(libs.androidxCollectionJs)
      }
    }

    val hostMain by creating {
      dependsOn(commonMain)
      dependencies {
        implementation(libs.okio.core)
      }
    }

    val jvmMain by getting {
      dependsOn(hostMain)
    }

    val nativeMain by getting {
      dependsOn(hostMain)
    }
  }

  targets.all {
    compilations.all {
      // Naming logic from https://github.com/JetBrains/kotlin/blob/a0e6fb03f0288f0bff12be80c402d8a62b5b045a/libraries/tools/kotlin-gradle-plugin/src/main/kotlin/org/jetbrains/kotlin/gradle/plugin/KotlinTargetConfigurator.kt#L519-L520
      val pluginConfigurationName = PLUGIN_CLASSPATH_CONFIGURATION_NAME +
        target.disambiguationClassifier.orEmpty().capitalize() +
        compilationName.capitalize()
      project.dependencies.add(pluginConfigurationName, projects.ziplineKotlinPlugin)
    }
  }
}

tasks {
  // https://kotlinlang.org/docs/whatsnew19.html#library-linkage-in-kotlin-native
  withType<KotlinNativeCompile>().configureEach {
    compilerOptions {
      freeCompilerArgs.addAll("-Xpartial-linkage-loglevel=ERROR")
    }
  }

  // https://youtrack.jetbrains.com/issue/KT-56025
  // https://youtrack.jetbrains.com/issue/KT-57203
  // Required for K1.
  findByName("jsBrowserProductionWebpack")?.apply {
    dependsOn(named("jsProductionLibraryCompileSync"))
    dependsOn(named("jsDevelopmentLibraryCompileSync"))
  }
  // Required for K1.
  findByName("jsBrowserProductionLibraryPrepare")?.apply {
    dependsOn(named("jsProductionExecutableCompileSync"))
    dependsOn(named("jsDevelopmentLibraryCompileSync"))
  }
  // Required for K2.
  findByName("jsBrowserProductionLibraryDistribution")?.apply {
    dependsOn(named("jsProductionExecutableCompileSync"))
    dependsOn(named("jsDevelopmentLibraryCompileSync"))
  }
}
