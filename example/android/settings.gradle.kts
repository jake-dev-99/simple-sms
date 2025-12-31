pluginManagement {
    val flutterSdkPath = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        val flutterSdkPath = properties.getProperty("flutter.sdk")
        require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
        flutterSdkPath
    }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // START: FlutterFire Configuration
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
    // END: FlutterFire Configuration
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include(":app")
// Add these lines to include the modules from the main project
// Path is relative to the example/android directory
include(":google_apps_messaging_core")
include(":google_i18n_libphonenumber")
include(":google_ex")
include(":google_chips")
include(":google_photoviewer")
include(":google_vcard")
project(":google_apps_messaging_core").projectDir = file("../../android/lib/google_apps_messaging_core")
project(":google_i18n_libphonenumber").projectDir = file("../../android/lib/google_i18n_libphonenumber")
project(":google_ex").projectDir = file("../../android/lib/google_ex")
project(":google_chips").projectDir = file("../../android/lib/google_chips")
project(":google_photoviewer").projectDir = file("../../android/lib/google_photoviewer")
project(":google_vcard").projectDir = file("../../android/lib/google_vcard")
