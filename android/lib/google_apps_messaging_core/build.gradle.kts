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
    namespace = "com.android.messaging"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("debug")
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {

    implementation(project(":google_i18n_libphonenumber"))
    implementation(project(":google_chips"))
    implementation(project(":google_photoviewer"))
    implementation(project(":google_vcard"))
    implementation(project(":google_ex"))

    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.appcompat:appcompat-resources:1.7.0")


    // Kotlin
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    // AppCompat - Important for Styles and Themes
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
    androidTestImplementation("androidx.work:work-testing:2.10.1")

    // Apache Commons Codec
    implementation("commons-codec:commons-codec:1.18.0")
}