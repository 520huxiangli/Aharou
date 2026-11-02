# 资产同步（硬规则）

`app/src/main/assets/prompts/`（AI 提示词）与 `docs-site/docs/`（用户文档）是 AI Agent 的知识来源，必须与代码同步：

- **AI 工作流改动 → 同步 `prompts/`**：工具增删改名、参数签名变化、agent 行为或提示词逻辑调整，都要更新 `prompts/` 下对应文件（自行查找，不存在则新建）。静态基线片段在 `prompts/` 顶层（`NN-名称.md`，常驻注入），按场景注入的叶子片段在 `prompts/agent/`；低频专门规则另放 `assets/rules/`（不进常驻提示词，由 `loadRule` 工具按需取正文，可被 `~/.aharou/rules/` 同名覆盖）。
- **功能或工具变化 → 检查 `docs-site/docs/`** 是否有使用文档要更新。
- **UI 变化（新增页面、改交互、调布局、改文案）→ 必须更新 `docs-site/docs/`**；新增文档页同步加进 `docs-site/.vitepress/config.ts` 侧栏与 `docs-site/docs/guide/overview.md` 索引。
- **新增或改动用户可配置项 → 必须接入 config 通道（硬规则）**：新功能带出任何用户可设置的项（开关、数值、路径、渠道凭证、行为偏好等），**当轮就自己接**——在 `feature/*/data/Config*Fields.kt` 里注册字段并接好读写，不要等用户提醒、不要留「以后再说」；敏感项标 `SENSITIVE` 走确认与审计，集合型设置走 add/remove。做法与既有风格参照项目记忆 `config_field_registration_pattern` 与 `aharou_config_channel_field_style`。
- **用户可见中文文案 → 必须进双语 strings.xml**：写入 `values/strings.xml`（中文）与 `values-en/strings.xml`（英文），代码用 `stringResource(R.string.xxx)` 引用。**禁止在 `.kt` 中硬编码中文 UI 文案。** 命名用语义化英文小写下划线，跨页面复用的加 `common_` 前缀。
- **用户可见功能变了 → 回填能力台账**：`docs/agent-user-parity.md` 对齐「用户在界面上能做的事」与「agent 有没有对应工具」，新增设置分区或增删工具时同步更新，免得缺口清单过期。

**文档目录约定**：`docs-site/docs/` 是文档唯一事实源，`guide/` 放功能说明、`advanced/` 放环境搭建与进阶教程。构建时由 `syncAiDocs` task（已挂 `preBuild`）生成进 APK 的 `assets/docs/`，AI 在容器内看到的是 `~/.aharou/docs/{guide,advanced}/*.md`（`~/.aicode/docs` 是同一份的兼容旧路径）。**面向用户书写**：讲清怎么做、会看到什么、出错怎么办；变量名、错误码、内部实现路径属于 `prompts/`，别写进用户文档。

**容器镜像清单由脚本生成，禁止手改**：`app/src/main/assets/container-images.json` 与 `data/container-images.json` 都由 `scripts/sync-container-images.py` 生成（拉上游版本 → 校验和 → 上传 cnb → 改写两份清单），手改会被下次同步覆盖，且校验和与产物会对不上。
