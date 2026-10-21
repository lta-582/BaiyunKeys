plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.baiyunkeys.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.baiyunkeys.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 签名库（baiyunkeys.jks）按惯例不入库，见 .gitignore。
            // 本地存在该文件时用它签名；clone 本仓库的人可在项目根目录放入
            // 自己的签名库（同名文件，或修改此处配置）后再构建 release。
            val keystoreFile = file("../baiyunkeys.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "baiyun123"
                keyAlias = "baiyunkeys"
                keyPassword = "baiyun123"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 没有签名库时退回 debug 签名，保证 clone 后可直接构建
            signingConfig = if (file("../baiyunkeys.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
}
