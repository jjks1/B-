plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dailycheckin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dailycheckin"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "1.0.0-fix23"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 让 JVM 测试能拿到合并后的 Android 资源：免责弹窗这类界面测试
    // 需要真实的主题与布局，否则 AppCompat 会报「不是 Theme.AppCompat」
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 仅测试代码使用：在 JVM 上跑真实的 Android 逻辑（不进 APK）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}

// Robolectric 运行时要去 Maven 拉 android-all，指到国内镜像
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
    systemProperty("robolectric.dependency.repo.id", "aliyun")
    // 沙箱根证书装在系统 trustStore，测试 JVM 也要指过去
    systemProperty("javax.net.ssl.trustStore", "/etc/ssl/certs/java/cacerts")
    systemProperty("javax.net.ssl.trustStorePassword", "changeit")
}
