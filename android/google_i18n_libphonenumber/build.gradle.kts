// build.gradle.kts for Android project
plugins {
    id("com.android.library")
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
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        create("profile") {
        }
    }
}

// Parent group and version information
group = "com.googlecode.libphonenumber"
version = "9.0.0"

// Adding required dependencies
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Add additional dependencies as needed
    testImplementation(libs.junit)
}
