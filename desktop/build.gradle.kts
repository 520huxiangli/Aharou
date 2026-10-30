plugins {
    // 版本同 :core-agent：KGP 已由 :app 的 kotlin("android") 带进 classpath，不重复声明
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.2.21"
    id("org.jetbrains.compose") version "1.10.3"
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

// 桌面壳：Compose Multiplatform 前端 + 复用 :core-agent 的领域层。
// 这里只有 UI 与平台接线，业务逻辑一律留在 :core-agent。
dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-storage"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // 纯 Swing 的代码编辑器，用 SwingPanel 嵌进 Compose；比嵌 Chromium 轻得多
    implementation("com.fifesoft:rsyntaxtextarea:3.4.0")
    // Compose 的事件循环基于 Swing，协程要跟着它调度
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.aharou.desktop.MainKt"
    }
}
