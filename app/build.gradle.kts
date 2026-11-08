plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "sh.hnet.music"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "sh.hnet.music"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "v0.1.0"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        generateLocaleConfig = true
        // Libraries' translations limited to the app's languages
        localeFilters += listOf("en", "pl")
    }
    // F-Droid: no dependency metadata blob in the APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Disable baseline profile generation for F-Droid reproducible builds
// https://f-droid.org/docs/Reproducible_Builds/
tasks.configureEach {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.sqlite.framework)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
