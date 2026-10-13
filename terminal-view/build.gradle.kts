plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
        // JDK 21 已弃用 source/target 8，会在编译期刷警告；升到 17 与 :app 对齐。
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.3.0")
    api(project(":terminal-emulator"))
}
