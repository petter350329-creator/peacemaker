plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minwoo.pacemaker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minwoo.pacemaker"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // 고정 서명키: 새 버전을 받아도 기존 앱 위에 그대로 업데이트 설치되게
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/pacemaker.jks")
            storePassword = "pacemaker"
            keyAlias = "pacemaker"
            keyPassword = "pacemaker"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
