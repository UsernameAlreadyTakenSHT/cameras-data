import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing, kept out of the repository: the properties file named by
// CAMERAS_KEYSTORE_PROPERTIES, else ~/.cameras/keystore.properties. Its storeFile is relative to
// that file. Without one, the release build stays unsigned.
val keystorePropsFile = listOfNotNull(
    System.getenv("CAMERAS_KEYSTORE_PROPERTIES")?.let(::File),
    File(System.getProperty("user.home"), ".cameras/keystore.properties"),
).firstOrNull { it.isFile }
val keystoreProps = Properties().apply {
    keystorePropsFile?.inputStream()?.use { load(it) }
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
        versionCode = 6
        versionName = "0.2.3"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = keystorePropsFile!!.parentFile.resolve(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: code and resource shrinking (the debug variant stays unoptimised).
            optimization {
                enable = true
            }
            // Phones only: the x86_64 map library serves emulators. `-PallAbis` keeps it, to try
            // the release build on an emulator.
            if (!project.hasProperty("allAbis")) {
                ndk {
                    abiFilters += "arm64-v8a"
                }
            }
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
