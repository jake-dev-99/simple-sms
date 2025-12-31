
// Parent group and version information
group = "com.android.ex.chips"
version = "9.0.0"


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
    namespace = "com.android.ex.chips"
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
            java.srcDirs("src")
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

dependencies {

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.appcompat:appcompat-resources:1.7.0")

    // Apache Commons Codec
    implementation("commons-codec:commons-codec:1.18.0")

}