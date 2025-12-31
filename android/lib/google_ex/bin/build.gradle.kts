// Copyright (C) 2010 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

repositories {
    google()
    mavenCentral()
}
plugins {
    id("com.android.library")
}

android {
    namespace = "com.android.ex"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        create("profile") {
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(
//                "camera2/public/src",
                "camera2/extensions/advancedSample/src/java",
//                "camera2/extensions/eyesFreeVidSample/src",
//                "camera2/extensions/sample/src/java",
//                "camera2/extensions/stub/src/main/java",
//                "camera2/portability/src",
                "common/java",
                "framesequence/src",
//                "widget/java",
            )
            res.srcDirs("res")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.0.13004108"
}

// Parent group and version information
group = "com.android.ex"
version = "9.0.0"

dependencies {
    // Kotlin
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.appcompat:appcompat-resources:1.7.0")
    implementation("com.google.guava:guava:33.4.8-android")
}