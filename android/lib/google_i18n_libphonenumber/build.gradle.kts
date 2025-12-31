// build.gradle.kts for Android project
repositories {
    google()
    mavenCentral()
}
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.google.i18n.phonenumbers"
    compileSdk = 35  // Updated to 35

    defaultConfig {
        minSdk = 30 // Default minimum SDK
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src")
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

    publishing {
        singleVariant("debug")
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Parent group and version information
group = "com.googlecode.libphonenumber"
version = "9.0.0"

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
}