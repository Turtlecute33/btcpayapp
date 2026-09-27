// AGP 9 has built-in Kotlin support, so `org.jetbrains.kotlin.android` is no
// longer applied here; only the two compiler plugins are.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing material.
 *
 * Nothing secret is in this repository, and nothing secret is written next to
 * it. The keystore lives outside the working tree — `~/.config/btcpayapp/release.jks`
 * by default, overridable with `BTCPAYAPP_KEYSTORE` — and its password comes
 * from the macOS Keychain, or from `BTCPAYAPP_KEYSTORE_PASSWORD` on a machine
 * without one. If either is missing the release build stays unsigned. It never
 * falls back to the debug key: an APK signed `CN=Android Debug` that looks
 * shippable is worse than one that plainly is not.
 */
val releaseKeystore: File? =
    providers.environmentVariable("BTCPAYAPP_KEYSTORE")
        .orElse(providers.systemProperty("user.home").map { "$it/.config/btcpayapp/release.jks" })
        .orNull
        ?.let(::File)
        ?.takeIf { it.isFile }

// Only a build that asks for a release task, or for assemble, build or bundle
// (which include one), reads the key. The signing block is evaluated on every
// configuration, so without this a debug build, a debug test run or an IDE
// sync on the release machine would read the Keychain for nothing. Gradle also
// runs abbreviations (`aR` is assembleRelease), so a later word that is the
// start of "Release" (R, Re, Rel, ...) counts too: a missed match gives an
// unsigned APK, a false one only an extra Keychain read.
val releaseRequested: Boolean = gradle.startParameter.taskNames.any { name ->
    val task = name.substringAfterLast(':')
    val laterWords = task.split(Regex("-|(?=\\p{Lu})")).drop(1).filter(String::isNotEmpty)
    "release" in task.lowercase() ||
        task.lowercase() in setOf("assemble", "build", "bundle") ||
        laterWords.any { "release".startsWith(it.lowercase()) }
}

val releaseKeystorePassword: String? by lazy {
    if (!releaseRequested) return@lazy null
    val fromEnvironment = providers.environmentVariable("BTCPAYAPP_KEYSTORE_PASSWORD").orNull
    if (!fromEnvironment.isNullOrEmpty()) return@lazy fromEnvironment
    if (!providers.systemProperty("os.name").getOrElse("").startsWith("Mac")) return@lazy null
    providers.exec {
        commandLine("security", "find-generic-password", "-s", "btcpayapp-release-keystore", "-w")
        isIgnoreExitValue = true
    }.standardOutput.asText.orNull?.trim()?.takeIf { it.isNotEmpty() }
}

android {
    namespace = "com.btcpayapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.btcpayapp"
        minSdk = 26
        targetSdk = 37
        versionName = "0.2.0"
        // Derived, so a release can never ship a versionName bump with a stale
        // versionCode: Android installs an update only when the code grows.
        // major.minor.patch -> major * 10000 + minor * 100 + patch.
        versionCode = versionName!!.split('.').map(String::toInt).let { (major, minor, patch) ->
            require(minor < 100 && patch < 100) { "minor and patch must stay below 100" }
            major * 10_000 + minor * 100 + patch
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP otherwise signs a Google-readable manifest of every dependency into
    // the APK signing block, and stamps the build's VCS state into META-INF.
    // Neither is something a wallet should hand to whoever inspects the file.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (releaseKeystore == null || releaseKeystorePassword == null) {
            if (releaseRequested) logger.warn(
                "No release signing material found — :app:assembleRelease will produce an " +
                    "UNSIGNED APK that no device will install.",
            )
        } else {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = "btcpayapp"
                keyPassword = releaseKeystorePassword
                // v1 adds nothing on minSdk 26 and leaves the signature readable
                // inside the zip directory; v2/v3 cover the whole APK.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            vcsInfo { include = false }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The Lightning node directory is memory-mapped out of the APK, which
        // only works while the entry is stored rather than deflated. It costs
        // about 90 KB of download and saves reading 200 KB onto the heap on the
        // first channel row. See core/lightning/BundledNodeIndex.kt.
        noCompress.add("lnnodes.bin")
        // The UI is English only. Without a filter every AndroidX library's
        // translations ship too, for strings this app never shows.
        localeFilters += listOf("en")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/DEPENDENCIES",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xconsistent-data-class-copy-visibility",
            // Material 3 Expressive and the adaptive navigation suite are still
            // annotated experimental in 1.4.0 even though they ship in the
            // stable artifact. Opting in here keeps the annotation noise out of
            // every file that draws a button.
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "-opt-in=androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material3.adaptive.nav.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.biometric)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
