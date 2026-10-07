plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.earbook.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.earbook.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // MediaSessionCompat / MediaStyle 通知（朗读服务的线控基础）
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // PDF 文本提取（Apache 2.0，com.tom-roush 是 Apache PDFBox 的 Android 移植）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // TTS 模型包解压（tar.bz2）——标准库无 BZip2
    implementation("org.apache.commons:commons-compress:1.27.1")

    // M3-2 预渲染（charging+idle 约束任务）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // instrumented smoke tests（Phase 1 CI）
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // JVM 单测（TextNormalizer/SentenceWindow 纯逻辑）
    testImplementation("junit:junit:4.13.2")
}
