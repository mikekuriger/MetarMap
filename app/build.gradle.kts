import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.0.0"
}

val localProperties = File(rootProject.rootDir, "local.properties")
val apiKey: String = if (localProperties.exists()) {
    val properties = Properties()
    properties.load(localProperties.inputStream())
    properties.getProperty("aviationstack_api_key")
        ?: throw IllegalArgumentException("API key 'aviationstack_api_key' not found in local.properties")
} else {
    throw FileNotFoundException("local.properties file not found.")
}

android {

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "app-${versionName}-${versionCode}.apk"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    namespace = "com.airportweather.map"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airportweather.map"
        minSdk = 24
        targetSdk = 35
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            buildConfigField("String", "AVIATIONSTACK_API_KEY", "\"$apiKey\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.play.services.location)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.play.services.maps.v1900)
    implementation(libs.androidx.appcompat)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.gms.play.services.maps)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.opencsv)
    implementation(libs.android.maps.utils)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.material.v190)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.core.splashscreen)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

}