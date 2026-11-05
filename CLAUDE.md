# CLAUDE.md

本仓库的 AI 协作规则（Claude Code、AiCode 等 AI 编程助手均读取本文件），优先级高于默认通用规则。

本文件只放**每次任务都要遵守**的条款；专题规则按目录拆在 `rules.d/`，由提示词侧按文件名顺序接在本文件后面一并注入，与写在这里等价。

## 规则文件索引

| 文件 | 管什么 |
| --- | --- |
| `rules.d/10-build-verify.md` | 构建闸门、命令选择、产物与交付、CI 全景 |
| `rules.d/20-architecture.md` | 架构地图、数据库与迁移 |
| `rules.d/30-assets-sync.md` | 提示词 / 文档 / config 通道 / 双语文案同步 |
| `rules.d/40-git-release.md` | 提交规范、分支工作流、PR 合并、发版 |
| `rules.d/50-issue-review.md` | Issue 评估与审查报告 |
| `rules.d/60-principles.md` | 产品与实现原则、记忆写作纪律 |

改规则时按主题就近改对应文件；新增片段的编号约定见 `rules.d/30-assets-sync.md`。

## 开工必做：先查记忆与规则（硬规则）

任何任务开始前，第一步是查，不是动手：

1. **`memory search`** 查既有结论——踩过的坑、定过的方案、路径与凭据位置、上次的决定。查到就用，不重造、不重问。
2. **查规则**：本文件 + `rules.d/`，以及按需规则（系统提示里列出的，对口的先 `loadRule`）。
3. 上面都没有，才去读代码、翻日志、试错。

判断出了问题、或准备动手时，先问「记忆里有没有人踩过这个坑」，再问「代码长什么样」。

> 2026-11-02 两翻车立此条：`git push` 断连时没查 `github_access`（里面写着完整 API 推送流程）；头像被覆盖时没查记忆，却 grep 代码查了三处。都是先重试、先翻代码，记忆最后才想起来。

## 收尾必做：逐项自检，不许说「已排期」（硬规则）

只要出现「做完了吗 / 全部做完了没有 / 还差什么」，或者准备汇报完成时：

1. **把要求逐条列成清单**（从对话与待办里抄，不凭印象）；
2. **每条用工具核实**（打开代码、grep 调用点、跑脚本），不靠「我记得改过」；
3. 标 ✅ 已做 / ❌ 未做 / ⚠️ 部分，**未做的当场做**；
4. 清单里没有 ❌ 才允许说「全部完成」。

**机械校验（不靠自觉）**：任务清单写在 `.aharou/task-list.md`（`- [ ]` / `- [x]`），汇报完成前必须跑
`python3 scripts/check_task_list.py`：脚本有未勾选项就非零退出并列出，**此时不许说「全部完成」**。
用户也可以自己跑这句验证。脚本通过后才把清单里的项逐个改 `- [x]`。

明确禁止：用「排期里、待办中、下个版本」代替完成；只报做过的部分让用户自己发现漏项。

## 基本约定

- Android 应用：Kotlin + Compose + Hilt + Coroutines/Flow，模块 `:app` / `:terminal-emulator` / `:terminal-view` / `:benchmark`。中间两个是 Termux 终端组件（包名 `com.termux.terminal` / `com.termux.view`），改终端功能优先动 `feature/terminal/`；`:benchmark` 是 Macrobenchmark，只在真机上用 `./gradlew :benchmark:connectedCheck` 生成 `app/src/main/baseline-prof.txt`，日常不碰。
- **优先使用项目自定义组件（硬规则）**：
  - **输入框**：优先使用 `core/ui/AppTextField.kt`（及其配套配色 `appTextFieldColors()`、弹窗专用 `dialogTextFieldColors()`），禁止在业务页面无故裸写带有长下划线的原生 `TextField`，禁止在各模块私自重复包装 OutlinedTextField。
  - **开关**：一律使用 `core/ui/AppSwitch.kt`，禁止使用原生 M3 `Switch`。
  - **模态底栏**：一律使用 `core/ui/AdaptiveModalBottomSheet.kt`（已集成平板自适应与 fling fix），禁止直接调用原生 `ModalBottomSheet`；长表单容器外层注意追加 `.imePadding()` 避让软键盘。
  - **分组卡片与列表行**：设置与表单页面优先复用 `feature/settings/presentation/component/SettingsGroupComponents.kt` 中的 `SettingsGroup`、`SettingsRow`、`SettingsDivider`、`SettingsGroupHeader`，保持统一卡片与层级质感。
  - **左滑删除**：一律使用 `core/ui/SwipeToDeleteRow.kt`。
  - **主题与设计规范**：严格遵循 `core/theme/AIEditorTheme.kt` 的 `Spacing`、`Radius`（含 `Radius.mdLarge = 12.dp`）与 `MaterialTheme.semanticColors`，禁止随意硬编码非标魔数与色彩。

### API 与编译红线（实战踩过）

- **minSdk 26**：用新 API 前先确认它在 Android 8.0 上存在。不要用 `InputStream.readNBytes(int)`（API 33+，低版本抛 `NoSuchMethodError`）——需要「最多读 N 字节」时用 `core/util/BoundedLineReader.kt` 的 `readFileHead`。`Array` 没有 `asReversed()`（那是 `MutableList` 的扩展），数组要用 `reversedArray()` / `reversed()`。
- **`AdaptiveModalBottomSheet` 的调用点必须自带 `@OptIn(ExperimentalMaterial3Api::class)`**：它的签名暴露了实验类型 `SheetState` / `ModalBottomSheetProperties`，删掉 OptIn 会直接编译失败（设置页灵魂图标弹层就踩过）。
