import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // KMP 与 KSP 的版本都来自根 classpath，不在模块里重复声明
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room") version "2.7.1"
}

// 数据库定义已搬到本模块，schema 也要跟着导出，否则迁移对账失去参照
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core-agent"))
            api("androidx.room:room-runtime:2.7.1")
            implementation("androidx.sqlite:sqlite-bundled:2.5.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.aharou.core.storage"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.1")
    add("kspJvm", "androidx.room:room-compiler:2.7.1")
}
