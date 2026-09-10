import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val kakaoNativeKey = providers.gradleProperty("KAKAO_NATIVE_APP_KEY")
    .orElse(localConfig.getProperty("KAKAO_NATIVE_APP_KEY", ""))
val dataGoServiceKey = providers.gradleProperty("DATA_GO_KR_SERVICE_KEY")
    .orElse(localConfig.getProperty("DATA_GO_KR_SERVICE_KEY", ""))

android {
    namespace = "kr.co.tesla.cameraalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.tesla.cameraalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.5.1"
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"${kakaoNativeKey.get()}\"")
        buildConfigField("String", "DATA_GO_KR_SERVICE_KEY", "\"${dataGoServiceKey.get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("sharedDebug") {
            // This is the existing debug key used by the installed WoongPilot builds. Keeping it
            // in the private repository lets another PC create an APK that Android can update.
            storeFile = rootProject.file("signing/woongpilot-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

android.applicationVariants.all {
    outputs.all {
        @Suppress("DEPRECATION")
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "WoongPilot.apk"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.kakaomobility.knsdk:knsdk_ui:1.12.7")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

