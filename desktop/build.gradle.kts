plugins {
    // 版本同 :core-agent：KGP 已由 :app 的 kotlin("android") 带进 classpath，不重复声明
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.2.21"
    id("org.jetbrains.compose") version "1.10.3"
}

// 与 Compose Multiplatform 1.10.3 配套的 skiko 版本
val skikoVersion = "0.9.37.4"

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

    // 交叉平台：容器是 linux-arm64，compose.desktop.currentOs 只带本机原生库。
    // 要把 jar 拿到 Windows 上跑，Windows 版的 skiko 必须一起打进去。
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    // Compose 的事件循环基于 Swing，协程要跟着它调度
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.aharou.desktop.MainKt"
        nativeDistributions {
            // jpackage 只能给当前平台出包，所以 msi 由 CI 的 Windows job 构建
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "AharouDesktop"
            packageVersion = "1.0.0"
            description = "Aharou 桌面版"
        }
    }
}

/**
 * 不依赖 jpackage 的胖 jar：拿到任何装了 JDK 17 的机器上 `java -jar` 就能跑。
 * jpackage 只能给当前平台出包，容器是 linux-arm64 出不了 exe，所以桌面端交付先走这条路。
 */
tasks.register<Jar>("desktopFatJar") {
    group = "distribution"
    description = "可跨平台运行的胖 jar（含 Windows 与当前平台的 skiko 原生库）"
    archiveFileName.set("AharouDesktop.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.aharou.desktop.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
