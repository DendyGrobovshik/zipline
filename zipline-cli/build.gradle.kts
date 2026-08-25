import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
  application
  id("com.github.gmazzo.buildconfig")
  id("com.vanniktech.maven.publish.base")
}

application {
  mainClass.set("app.cash.zipline.cli.Main")
}

buildConfig {
  useKotlinOutput {
    internalVisibility = true
  }

  packageName("app.cash.zipline.cli")
  buildConfigField("String", "VERSION", "\"${version}\"")
}

// Disable .tar that no one wants.
tasks.named("distTar").configure {
  enabled = false
}

// Remove default .jar output artifact.
configurations.archives.configure {
  artifacts.clear()
}
// Add the distribution .zip as an output artifact.
artifacts {
  archives(tasks.named("distZip"))
}

kotlin {
  sourceSets {
    all {
      languageSettings.optIn("app.cash.zipline.EngineApi")
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
  }
}

dependencies {
  implementation(projects.ziplineApiValidator)
  implementation(projects.ziplineLoader)
  implementation(libs.clikt)
  implementation(libs.okHttp.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(projects.ziplineLoaderTesting)
  testImplementation(libs.assertk)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.okio.core)
  testImplementation(libs.okHttp.mockWebServer)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}

// CDP-debug expectations differ between prod (optimized, no debug info) and
// debug (unoptimized, debug info) compilation; the compiler test reads this.
tasks.withType<Test>().configureEach {
  systemProperty("hermesProd", providers.gradleProperty("hermesProd").orNull ?: "true")
}
