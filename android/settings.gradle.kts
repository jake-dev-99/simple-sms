// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Contents of this file should be generated automatically by
// dev/tools/bin/generate_gradle_lockfiles.dart, but currently are not.
// See #141540.
rootProject.name = "simple-sms"

pluginManagement {
    val flutterSdkPath = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        val flutterSdkPath = properties.getProperty("flutter.sdk")
        require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
        flutterSdkPath
    }

//    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
//    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}

//include(":simple-sms")
// The vendored `android/lib/google_*` modules (apps_messaging_core,
// i18n_libphonenumber, ex, chips, photoviewer, vcard) were build-time
// reference scaffolding from the original port and are not sourced by
// this plugin — removed entirely (UNFY-158). apps_messaging_core was
// already removed during the inbound-MMS port (its
// `MmsUtils.insertReceivedMmsMessage` is now `InboundMmsPersister.kt`).
