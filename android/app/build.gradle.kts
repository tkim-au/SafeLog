plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.safelog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.safelog.app"
        minSdk = 28 // Pleos 시작 가이드 기준
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
        // Optional ledger mirror. Empty = the car keeps everything locally (works offline, as on the judges' emulator).
        buildConfigField("String", "SAFELOG_SERVER", "\"\"")
    }

    // AAOS CarPropertyManager (speed, gear, night): the platform's optional android.car library
    useLibrary("android.car")

    // 팀 공용 디버그 서명 키 (디버그 전용, 공개돼도 됨). 사람마다 ~/.android/debug.keystore가 달라서
    // GitHub Actions가 만든 APK(서버 자동 배포)와 각자 만든 APK를 서로 덮어 설치하지 못하던 것을 막는다.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // the emulator reaches the Mac's localhost at 10.0.2.2 (backend/server: uvicorn ... --port 8000)
            buildConfigField("String", "SAFELOG_SERVER", "\"http://10.0.2.2:8000\"")
        }
        release {
            // 성능 측정은 이 빌드로 한다. debug의 Compose는 훨씬 느리다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Pleos SDKs are called by reflection (vehicle/Reflect.kt), so the app also builds without them:
//   ./gradlew assembleDebug -PpleosSdk=false   (plain AAOS emulator; speed/gear/night still come from AAOS)
val pleosSdk = (findProperty("pleosSdk") as String?)?.toBoolean() ?: true

dependencies {
    implementation(project(":core"))
    if (pleosSdk) {
        implementation(libs.pleos.vehicle)
        implementation(libs.pleos.navi)
        implementation(libs.pleos.vehicle.data)
        implementation(libs.pleos.tts)          // the praise line in the car's own voice (live/CarVoice.kt)
    }
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
