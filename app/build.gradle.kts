plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        // Versioning scheme and bump rules: docs/PROCESS.md section 3. versionCode is monotonic
        // and never reused; versionName is MAJOR.MINOR.PATCH. Pre-1.0 means proven on the bench
        // and on validation walks, not yet proven on a paid engagement -- see the v1.0.0 gate in
        // docs/ROADMAP.md. This is the single source of the version; anything displaying it reads
        // from here.
        versionCode = 9
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}