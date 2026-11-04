# Release 签名与打包（Aharou）

> 2026-09-26 建立，2026-11-04 按现状重写。本节只讲签名与打包，不含任何口令。
> 发版节奏与 tag 流程见 `rules.d/40-git-release.md`。

## 正式包参数

- applicationId：`com.aharou.agent`（与 `.debug` / `.beta` 变体同机共存、互不覆盖）
- 应用名（标签）：**Aharou**
- R8 混淆 + 资源压缩：开（release 与 beta 同款配置）
- **只发两个单架构包，不发 universal**（两套容器镜像塞在一起体积翻倍）：
  - `armsolo`：仅 arm64-v8a（真机全部是 arm64）
  - `x86solo`：仅 x86_64（模拟器 / Chromebook）
- 产物：`app/build/outputs/apk/armsolo/release/app-armsolo-release.apk`、
  `app/build/outputs/apk/x86solo/release/app-x86solo-release.apk`
- 发布时重命名为 `Aharou-arm64-<版本>.apk` / `Aharou-x86_64-<版本>.apk`，另附 `mapping.txt.gz` 供崩溃反混淆。

## 签名密钥（一律不入库）

`.gitignore` 已挡 `*.jks` / `*.keystore` / `keystore.properties`，仓库里不应出现任何签名文件。

CI 从四个 Repository Secrets 还原：

| Secret | 用途 |
| --- | --- |
| `AICODE_KEYSTORE_BASE64` | jks 的 base64 全文，`base64 -d` 还原到 `app/aicode.jks` |
| `AICODE_KEYSTORE_PASSWORD` | storePassword |
| `AICODE_KEY_ALIAS` | keyAlias（取自 secret，不在代码里写死） |
| `AICODE_KEY_PASSWORD` | keyPassword |

还原后生成 `app/keystore.properties`（`storeFile=aicode.jks`），路径与 `app/build.gradle.kts` 期望一致。缺任一步会出 unsigned 包。

⚠️ 密钥丢失 = 已安装用户永远无法覆盖升级；口令与 jks 备份由主人保管，不写进仓库与文档。

## 本地打包

`gradlew` 有编译闸门：没有 `.aharou/build-allowed` 时拒绝执行任何 Gradle 任务（包括单测）。
先取得主人明确同意，再写授权文件（`quote` 必须含「编 / build / 打包 / APK / 出包」之一）：

```
authorized_by: 主人
quote: "编译出正式包"
at: 2026-11-04T21:00
```

`at` 超过 30 分钟作废，令牌消费一次即删，所以要连着单测一起写进同一条命令：

```sh
./gradlew :app:assembleArmsoloRelease :app:assembleX86soloRelease
```

本地要自己出 release 包时，还需把 jks 与 `app/keystore.properties` 从备份还原到上述路径。

## 产物校验

```sh
apksigner verify --print-certs <apk>    # 验签，顺带打印证书指纹
aapt dump badging <apk> | head          # 包名 / 标签 / 版本
```

## 备注

- 正式发版走 `android-release.yml`：push `v*` tag 触发，先校验 tag 位于 master 历史中，再跑迁移对账、
  技能自检、架构门禁与单测，最后构建两个单架构 APK 并发布 GitHub Release。
- `beta` 变体（`.beta` 后缀）继承 release 全部配置，用于测机共存验证；由 `beta.yml` 在 push `master`
  时构建，产物发到固定 tag `beta-latest` 的 prerelease。
- 分发前自查：仓库与 APK 零个人数据（见 fusion-plan 分发原则）。
