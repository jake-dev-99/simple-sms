// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

plugins {
    id("com.android.library")
    id("dev.flutter.flutter-gradle-plugin")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.android.providers.telephony"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.0.13004108"

//     Flutter's CI installs the NDK at a non-standard path.
//     This non-standard structure is initially created by
//     https://github.com/flutter/engine/blob/3.27.0/tools/android_sdk/create_cipd_packages.sh.
    val systemNdkPath: String? = System.getenv("ANDROID_NDK_PATH")
    if (systemNdkPath != null) {
        ndkPath = systemNdkPath
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 30
    }

    buildTypes {
        named("release") {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    implementation(project(":google_apps_messaging_core"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)

    // Kotlin
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.material)

    // Role Manager
    implementation(libs.androidx.core.role)

    // Work Manager
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.guava)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.rxjava2)
    implementation(libs.androidx.work.gcm)
    implementation(libs.androidx.work.multiprocess)
    androidTestImplementation(libs.androidx.work.testing)

    // Apache Commons Codec
    implementation(libs.commons.codec)
}


flutter {
    source = "../.."
}
