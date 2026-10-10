import org.gradle.kotlin.dsl.implementation

// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

group = "io.simplezen.simple_sms"
version = "1.0-SNAPSHOT"

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.9.2")
    }
}

rootProject.allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    // id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "io.simplezen.simple_sms"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }

    defaultConfig {
        minSdk = 30
    }

    sourceSets {
        getByName("main") {
            // The MMS implementation and shared messaging code are Kotlin-only (UNFY-123).
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
//        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
//        getByName("test").java.srcDirs("src/test/kotlin")
    }
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    // Phase 1 safety net (UNFY-118): JVM unit tests via Robolectric so the
    // first-party codec + handlers can be exercised on a plain JVM,
    // no emulator. CI wiring to actually run these is L6 (UNFY-149).
    // `isIncludeAndroidResources` lets Robolectric load the merged
    // manifest/resources. (Deliberately no `isReturnDefaultValues` — that
    // governs the non-Robolectric mockable-android.jar path; under
    // RobolectricTestRunner un-shadowed framework calls should fail loudly
    // rather than silently return defaults.)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
//    implementation(project(":simple_sms"))

    // simple_permissions_android's PermissionGuards — used by Query.kt
    // + MessagingHelper.kt for runtime permission checks. Flutter's
    // plugin-loader (applied in the final app's settings.gradle.kts)
    // registers `:simple_permissions_android` alongside `:simple_sms`
    // when the app's pubspec graph includes both. Our root
    // simple_sms_native pubspec declares simple_permissions_native as
    // a runtime dep, so the plugin-loader picks it up transitively —
    // this project ref resolves in every app that consumes simple-sms.
    implementation(project(":simple_permissions_android"))

    // simple_query_android's ContentQuery — used by Query.kt +
    // internal consumers (MmsDatabaseWriter via Query(context).query)
    // to route all content-provider reads through simple_query.
    // Same plugin-loader pattern as simple_permissions_android above.
    implementation(project(":simple_query_android"))

    // First-party messaging needs framework APIs, FileProvider, and serialization.
    // Legacy HTTP transports, carrier XML themes, and unused UI/work libraries
    // were removed with the final MMS teardown (UNFY-123).
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // ── Phase 1 native unit-test net (UNFY-118) ──────────────────────────
    // Robolectric runs the first-party codec + handler logic on a plain JVM
    // (PduParser needs android.util.Log; PduComposer needs Context/TextUtils
    // — all Robolectric-shadowed). Mocking libs (mockk) are added by the
    // handler leaves L4/L5 when first used, so declared == used per PR.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
