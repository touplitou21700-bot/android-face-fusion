
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pv.androidfacefusion"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        // Identifiant distinct pour conserver la V1.
        applicationId = "com.pv.androidfacefusion.v2"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(
                listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    implementation(libs.onnxruntime.android)

    // Glide 4.16.0 compatible avec compileSdk 36.
    // Empeche la selection automatique de Glide 5.x.
    implementation("com.github.bumptech.glide:glide") {
        version {
            strictly("4.16.0")
        }
    }

    implementation(libs.opencv)

    // Etape 25 : segmentation cheveux et visage.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
