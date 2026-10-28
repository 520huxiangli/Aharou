buildscript {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
    dependencies {
        // 覆盖 AGP 8.9.3 自带的 R8 8.9.x。Kotlin 2.2 官方最低要求 R8 8.10.21，但该版本对
        // Kotlin 2.2.21 的 stdlib metadata（叠加核心库脱糖逐类重写）仍会抛无害的
        // "Should never be called" 并逐类刷屏（2000+ 条 WARNING，日志达 40 MB）。
        // 升到 8.13.19（官方 Kotlin 2.3 档，AGP 8.9.3 在其兼容区间 8.2.2-8.13 内，向下兼容 2.2 class）消除刷屏。
        // 必须放在根项目 buildscript：放 settings.gradle.kts 的 buildscript 里只影响 settings 脚本自身
        // 的 classpath，AGP 仍用它捆绑的版本，实测无效。
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.test") version "8.9.3" apply false
    id("androidx.baselineprofile") version "1.3.4" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.dagger.hilt.android") version "2.56.1" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
