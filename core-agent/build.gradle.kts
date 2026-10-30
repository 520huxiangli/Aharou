plugins {
    // 不带版本：Kotlin Gradle Plugin 已由 :app 的 kotlin("android") 带进根 classpath，
    // 这里再写 version 会报「already on the classpath」。
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.21"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 本模块只放与运行平台无关的 agent 内核：数据模型、provider 适配、工具契约、提示词。
// :app 与 :desktop 都依赖它。包名与 :app 保持完全一致，搬过来的代码无需改 import。
dependencies {
    // api 而非 implementation：调用方（:app / :desktop）直接使用这些库的类型。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-gson:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.code.gson:gson:2.10.1")
    api("javax.inject:javax.inject:1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.11")
}
