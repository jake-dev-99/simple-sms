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
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.android.vcard"
    compileSdk = 35  // Updated to 35

    defaultConfig {
        minSdk = 30
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("java")
            resources.srcDirs("src/com/google/i18n/phonenumbers/data")
        }
        getByName("test") {
            java.srcDirs("test")
            resources.srcDirs("test/com/google/i18n/phonenumbers/data")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        create("profile") {
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    sourceSets {
    }

    publishing {
        singleVariant("debug")
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Parent group and version information
group = "com.android.vcard"
version = "9.0.0"

// Adding required dependencies
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.appcompat:appcompat-resources:1.7.0")
    implementation("androidx.core:core-ktx:1.17.0")
}
