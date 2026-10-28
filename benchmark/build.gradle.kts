plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

/**
 * Macrobenchmark 模块：在真机/模拟器上跑出真实的启动与交互热路径，产出 baseline profile。
 *
 * 产出的 profile 比手写的准得多——手写只能猜「onCreate 里必然执行什么」，
 * 而这里采样的是真实调用链（Compose 首帧、Hilt 注入、数据库打开、配置表注册…）。
 *
 * 用法（需连接一台 API 28+ 的真机或模拟器，且 App 已装上 release/beta 包）：
 *   ./gradlew :benchmark:connectedCheck
 * 跑完把 benchmark/build/outputs/managed_device_android_test_additional_output/ 下的
 * baseline-prof.txt 覆盖到 app/src/main/baseline-prof.txt 即可。
 */
android {
    namespace = "com.aharou.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    // 被测应用启动的 Activity：与 app 模块 manifest 里的主入口一致。
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}

baselineProfile {
    // 不接管 app 模块已有的 profile 依赖，只在需要时重新生成。
    useConnectedDevices = true
}
