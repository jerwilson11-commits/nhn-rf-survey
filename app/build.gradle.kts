import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. keystore.properties is gitignored and never committed -- see
// docs/release-signing.md for how to generate it. Its absence (e.g. on a debug-only
// checkout, or in CI without secrets configured) must not break the build: release just
// comes out unsigned in that case, exactly as it does today.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.nhnengineering.rftest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nhnengineering.rftest"
        minSdk = 31
        // Deliberately 36, not 37, though the test device runs Android 17. Keeps API-37
        // behavior changes (mandatory ACCESS_LOCAL_NETWORK, large-screen orientation
        // enforcement) out of Phases 1-4. See docs/Android 17 Impact Notes.md.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    // The three modem helpers (libqmilock.so, libqmihelper.so, libdcilogger.so) are real
    // executables that `su -c` runs directly, not JNI code loaded with System.loadLibrary --
    // they need to exist as standalone files on disk, not stay zipped inside the APK, which is
    // the modern default for native libraries and (confirmed empirically on a Pixel 6 Pro test
    // install, 2026-09-28) leaves nativeLibraryDir present but empty without this.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.billing.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}