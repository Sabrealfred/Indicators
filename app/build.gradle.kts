import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.neopal.pet"
    compileSdk = 35

    // The versionCode every build carries.
    //
    // It has to increase for the in-app updater to mean anything: versionCode is the only
    // number Android itself orders installs by, and a stream of builds that all say 1 is a
    // stream the updater can only ever call "same". CI passes its run number, which is
    // monotonic per repository and never reused. A local build with nothing set stays at 1,
    // which is correct — a build made on this machine is not published and nothing should
    // ever offer it as an update.
    val buildNumber = (System.getenv("NEOPAL_VERSION_CODE") ?: "1").toIntOrNull() ?: 1

    signingConfigs {
        // A debug key that is committed to the repository, on purpose.
        //
        // Android refuses to install an update signed by a different key than the copy already
        // on the device — that is the whole basis of app identity, and it is right. Gradle's
        // default debug key is generated per machine, so every CI runner produced an APK with a
        // different signature and "update" meant "uninstall first, losing everything". That is
        // exactly the outcome this app exists to avoid.
        //
        // So the key lives here. It is not a secret and must never be treated as one: anyone
        // with the repository has it, the password is in this file, and it signs nothing but
        // debug builds under an applicationId ending in `.debug`. A release to Play would need
        // a real key, kept out of the repository, and this one must never be used for it.
        getByName("debug") {
            storeFile = file("neopal-debug.keystore")
            storePassword = "neopal-debug"
            keyAlias = "neopal"
            keyPassword = "neopal-debug"
        }
    }

    defaultConfig {
        applicationId = "com.neopal.pet"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // The first native code this app has ever carried, and it changes which handsets can
        // install it. Read this before assuming it is a formality.
        //
        // litertlm-android ships binaries for android_arm64 and android_x86_64 and nothing else —
        // 32-bit ARM is not supported by the library at all — and Google's own sample apps filter
        // to arm64-v8a. This does the same.
        //
        // The cost is real and it is not this feature's alone to pay. Until now the APK had no
        // `lib/` directory whatsoever, so it installed on anything from API 24 up, armeabi-v7a
        // included. An APK that *has* native libraries and none matching the device is refused by
        // the installer outright, so those handsets stop being able to install NeoPal at all —
        // not "without the on-device brain", at all. The alternative is per-ABI split APKs, which
        // the in-app updater is not built for: it downloads one artifact from one release.
        //
        // Left as a single filter deliberately rather than solved quietly, because it is a
        // product decision about who can play, not a build detail. It is still open.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Named explicitly rather than left to the default, which is the per-machine key.
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Lets the creature's art be drawn into a recorder on a plain JVM, which is what
            // CreatureArtInvariantsTest does. Drawing a creature goes through `Path()`, and on a
            // unit-test JVM that resolves to the stubbed `android.graphics.Path` — which throws
            // on every call unless this is set.
            //
            // Without it those tests do not fail, they *skip*, and a suite that silently skips
            // reads exactly like a suite that passes. That is the same shape as the two silent
            // failures already recorded in docs/PLAN.md, and it is the reason this line is here
            // rather than the tests being left to quietly do nothing.
            //
            // Safe for everything else: it can only turn a throw into a default, and no other
            // test in this project touches `android.*` at all.
            isReturnDefaultValues = true
        }
    }
}

// Replaces `android { kotlinOptions { jvmTarget = "17" } }`, which the Kotlin Gradle plugin no
// longer merely deprecates — from this version the String setter is an *error*, so the build
// script itself stops compiling and no task is ever scheduled. That is what the first attempt at
// this upgrade hit, in 24 seconds, before touching a line of Kotlin source.
//
// Worth recording because the diagnosis in hand was wrong: the risk everyone expected was the
// Compose compiler plugin moving against a frozen compose runtime. The plugins resolved and
// loaded without complaint. It was a two-line DSL migration in this file.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)

    // The engine for a model that runs on the handset. Resolved from google(), which
    // settings.gradle.kts already lists first — Maven Central returns 404 for these coordinates.
    //
    // Unverified from here, and the honest list of what that means: Google Maven is blocked from
    // this environment, so the coordinates have never been resolved by this machine, the
    // artifact's own declared minSdk has never been read against this module's 24, and no
    // transitive dependency it drags in has been seen. Every one of those is a way this line
    // alone turns a build red, which is why it is a commit of its own rather than folded into the
    // code that needs it.
    //
    // What *is* known, and it is why this version and not a newer one: CI resolved and downloaded
    // exactly these coordinates once already (0d8adce), so this version is published and this
    // repository can reach it. The compile then failed on Kotlin metadata 2.3.0 against a 2.0.21
    // compiler, which is the thing 7835c34 fixed.
    implementation(libs.litertlm.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
