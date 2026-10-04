<!-- Android/Gradle 构建排障：构建命令选择、环境前提、常见失败与规避 -->
# Android 构建排障

## 命令选择

- 日常冒烟编译只构一个变体，不要用聚合任务（`assembleDebug` / `build` / `test` 会跨所有 flavor 全跑，耗时成倍）。
- 推送前通常还要跑项目自带的校验脚本（迁移对账、架构门禁、技能自检等），以项目规则里写的命令为准。
- 单元测试在容器内可能因 Robolectric 要下载 `android-all-instrumented`（约 190MB，不走 Gradle 镜像）而永久挂起：worker 卡在 `futex_wait`、CPU 近 0。**跑不动就跳过交给 CI，不要反复重试。**

## 环境前提

- 容器内构建通常需要显式给 JDK（如 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64`）与 SDK 路径；工作区若有 `local.properties` 则以它为准。
- 先确认没有另一个 Gradle 构建在跑（重复实例会抢锁、报 `Timeout waiting to lock journal cache` 之类）。

## 常见失败

- **不要用 `--rerun-tasks`**：与 dex 增量缓存冲突，会在 `dexBuilder*` 上报 `NoSuchFileException`，之后构建停在 `UP-TO-DATE`（日志仍写 SUCCESSFUL 但 APK 不更新）。要强制重编就删中间产物。
- **编译期间不要改源码**：大改名/大重构后先清 KSP/Hilt 缓存（`kspCaches`、`generated/ksp`、`generated/hilt`、`hilt_aggregated_deps`）再编。
- **失败要读完整报错**：Gradle / AAPT2 / Kotlin 编译错误的原文才是根因，不要只回「编译失败」。
- 产物路径形如 `app/build/outputs/apk/<flavor>/<buildType>/app-<flavor>-<buildType>.apk`；构建失败不会有产物，别拿旧产物当成功。
- 长构建放后台并靠真实信号判断（日志出现 `BUILD SUCCESSFUL`、退出码 0、产物文件时间戳），不要睡够时间就当好了。
