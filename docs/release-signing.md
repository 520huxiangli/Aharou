# Release 签名与打包（Aharou）

> 2026-09-26 建立。正式包 = `universalRelease` 变体；本节只讲签名/打包，不含任何口令。

## 正式包参数

- applicationId：`com.aharou.agent`（与 `.debug` / `.beta` 变体同机共存、互不覆盖）
- 应用名（标签）：**Aharou**
- R8 混淆 + 资源压缩：开（release 与 beta 同款配置）
- 首个正式包：`app-universal-release.apk`（`1.11.0-dev.197+82fe7a88`，18.7 MB，arm64-v8a + x86_64）

## 签名密钥（一律不入库）

- 密钥文件：`keystore/aharou-release.jks`（`.gitignore` 已挡 `*.jks` / `keystore.properties`）
- 构建机属性文件：`app/keystore.properties`（`storeFile=../keystore/aharou-release.jks`）
- 密钥别名：`aharou`
- 证书 SHA-256 指纹：
  `15:EB:1B:0A:F0:DA:BB:68:CA:7C:31:DE:AA:3C:78:6E:A3:FB:7D:E3:96:84:AD:BE:94:51:3C:86:95:9A:B3:C1`
- **口令与备份**：运维目录 `shared/ops/aharou-release/`（口令在 `ops_secrets.sh` 的 `AHAROU_KS_PW`）。
  ⚠️ 密钥丢失 = 已安装用户永远无法覆盖升级，务必备份！

## 打包与校验

```sh
./gradlew :app:assembleUniversalRelease
# 产物：app/build/outputs/apk/universal/release/app-universal-release.apk

apksigner verify --print-certs <apk>    # 验签
aapt dump badging <apk> | head          # 包名/标签/版本
```

沙箱内 aapt（v1）需 qemu 包装执行（`setup_local.sh` 只包了 aapt2/aidl/zipalign）：

```sh
/usr/bin/qemu-x86_64 -L $TOOLCHAIN/x86root $SDK/build-tools/36.0.0/aapt dump badging <apk>
```

## 备注

- GitHub Actions 打包正式包时需注入密钥（Repository Secrets），**不要把 jks / keystore.properties 提交进仓库**。
- `beta` 变体（`.beta` 后缀）继承 release 全部配置，用于测机共存验证。
- 分发前自查：仓库与 APK 零个人数据（见 fusion-plan 分发原则）。
