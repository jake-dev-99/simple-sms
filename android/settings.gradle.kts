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
include(":google_apps_messaging_core")
include(":google_i18n_libphonenumber")
include(":google_ex")
include(":google_chips")
include(":google_photoviewer")
include(":google_vcard")

// project(":simple-sms").projectDir = file("/Users/jake/Development/simple-sms/android")
project(":google_apps_messaging_core").projectDir = file("/Users/jake/Development/consumer/simple-sms/android/lib/google_apps_messaging_core")
project(":google_i18n_libphonenumber").projectDir = file("/Users/jake/Development/consumer/simple-sms/android/lib/google_i18n_libphonenumber")
project(":google_ex").projectDir = file("/Users/jake/Development/consumer/simple-sms/android/lib/google_ex")
project(":google_chips").projectDir = file("/Users/jake/Development/consumer/simple-sms/android/lib/google_chips")
project(":google_photoviewer").projectDir = file("/Users/jake/Development/consumer/simple-sms/android/lib/google_photoviewer")
project(":google_vcard").projectDir = file("/Users/jake/Development/consumer/simple-sms/android/lib/google_vcard")
