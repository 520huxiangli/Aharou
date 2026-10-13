plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test> {
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
