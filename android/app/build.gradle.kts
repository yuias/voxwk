import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Short git hash of the build, surfaced in Settings → About. "unknown" if git
// is unavailable. Uses providers.exec so it stays configuration-cache safe
// (a raw ProcessBuilder at configuration time is rejected by the CC).
fun gitHash(): String =
  try {
    providers.exec {
      commandLine("git", "rev-parse", "--short", "HEAD")
      isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
  } catch (e: Exception) {
    "unknown"
  }

// Release signing credentials, read from keystore.properties (local builds,
// gitignored) or environment variables. When none are present the release
// build is left unsigned, so debug builds and secret-less runs still succeed.
val keystoreProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
  (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }
val releaseStorePath = signingValue("storeFile", "VOXWK_KEYSTORE_FILE")

// The ABIs the Rust core and the sherpa prebuilts are produced for. Declared
// here so the packaging filter below and the native build tasks cannot drift apart.
val androidAbis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "app.rly3h.voxwk"
    // AndroidX 2026.08 (core-ktx 1.19, compose-ui 1.12) requires compiling
    // against 37. targetSdk stays at 36 so no runtime behavior changes ride
    // along with the dependency bump.
    compileSdk = 37
    defaultConfig {
        applicationId = "app.rly3h.voxwk"
        // 31: Conversation routing and the Glass HFP route both need setCommunicationDevice.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_HASH", "\"${gitHash()}\"")

        // Without this the JNA aar drags libjnidispatch.so in for every ABI it
        // ships. Those stubs are enough for an armeabi-v7a/x86 device to accept
        // the install, and the app then dies on the first call into a core that
        // was never built for it.
        ndk { abiFilters += androidAbis }
    }

    // Only the languages that have a strings.xml are advertised to the system;
    // resources.properties sets the default (unqualified) locale.
    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        // Only declared when credentials are available; otherwise the release
        // build falls through to unsigned rather than failing configuration.
        if (releaseStorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = signingValue("storePassword", "VOXWK_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VOXWK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VOXWK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct applicationId so a debug build can sit alongside a
            // release build on the same device (their signing keys differ, so
            // they cannot overwrite each other under one id).
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

androidComponents {
    onVariants { variant ->
        // An unsigned release keeps a distinct name so it cannot be mistaken for
        // an installable build.
        val apkName = when {
            variant.buildType == "debug" -> "voxwk.debug.apk"
            releaseStorePath != null -> "voxwk.apk"
            else -> "voxwk-unsigned.apk"
        }
        variant.outputs.forEach { it.outputFileName.set(apkName) }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Settings, model downloads and the launch splash
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.core.splashscreen)

  // Model provisioning: parse the JSON manifest + extract tar.bz2 archives in-app.
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("org.apache.commons:commons-compress:1.28.0")

  // Online mode (OpenAI Realtime): WebSocket transport + on-device API-key encryption.
  implementation(libs.okhttp)
  implementation(libs.tink.android)
}
