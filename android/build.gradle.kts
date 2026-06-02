import org.gradle.kotlin.dsl.implementation
import org.gradle.api.tasks.compile.JavaCompile

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
            // Compile only the pieces we need: Klinker SMS/MMS and the minimal MMS PDU stack
            java.srcDirs("src/main/java")
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
//        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
//        getByName("test").java.srcDirs("src/test/kotlin")
    }
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.14904198"

    // Phase 1 safety net (UNFY-118): JVM unit tests via Robolectric so the
    // pdu_alt codec + first-party handlers can be exercised on a plain JVM,
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

    // `google_apps_messaging_core` removed in the inbound-MMS port.
    // Its `MmsUtils.insertReceivedMmsMessage` was the canonical reference
    // we ported into `InboundMmsPersister.kt`; with that port complete,
    // nothing in this module's runtime code calls into Bugle anymore.
    // PduPersister + the Klinker send_message tree under
    // `src/main/java/com/{google,android,klinker}/...` remain — those
    // are still used directly by inbound (PduPersister) and outbound
    // (Klinker Transaction). Their removal is a separate follow-up.
    implementation(project(":google_i18n_libphonenumber"))
    implementation(project(":google_chips"))
    implementation(project(":google_photoviewer"))
    implementation(project(":google_vcard"))
    implementation(project(":google_ex"))

    implementation("com.google.firebase:firebase-crashlytics-buildtools:3.0.3")

    implementation("com.squareup.okhttp:okhttp:2.5.0")
    implementation("com.squareup.okhttp:okhttp-urlconnection:2.5.0")

    implementation("com.squareup.okhttp3:okhttp:3.14.9")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:3.14.9")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Kotlin
    implementation("com.beust:klaxon:5.5")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    // AppCompat - Important for Styles and Themes
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.appcompat:appcompat-resources:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Role Manager
    implementation("androidx.core:core-role:1.1.0")

    // Work Manager
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.work:work-rxjava2:2.10.1")
    implementation("androidx.work:work-gcm:2.10.1")
    implementation("androidx.work:work-multiprocess:2.10.1")
//    androidTestImplementation("androidx.work:work-testing:2.10.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Apache Commons Codec
    implementation("commons-codec:commons-codec:1.18.0")

    // ── Phase 1 native unit-test net (UNFY-118) ──────────────────────────
    // Robolectric runs the pdu_alt codec + handler logic on a plain JVM
    // (PduParser needs android.util.Log; PduComposer needs Context/TextUtils
    // — all Robolectric-shadowed). Mocking libs (mockk) are added by the
    // handler leaves L4/L5 when first used, so declared == used per PR.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// Java compilation is enabled only for the Klinker package path specified in sourceSets above.
