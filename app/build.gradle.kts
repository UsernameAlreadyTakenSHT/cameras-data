plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.usernamealreadytakensht.cameras"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.usernamealreadytakensht.cameras"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            vcsInfo.include = false
        }
    }
    // No Google-encrypted dependency metadata in the signing block (F-Droid rejects the opaque blob).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Real org.json for the JVM tests: the one in android.jar is a stub.
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
