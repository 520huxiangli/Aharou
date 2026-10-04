<!-- 工程类型规约（Flutter）：依赖与常用命令、容器内的现实约束 -->
### Flutter 工程规约
- 先读 `pubspec.yaml` 的依赖与 `lib/` 目录结构，再决定改哪里。
- 常用命令：`flutter pub get`、`flutter analyze`、`flutter build apk --debug`。
- **容器内可能没有 Flutter SDK**：缺就先说明要装什么、装在哪并确认，不要假定已安装、也不要静默换方案。
- 改完至少跑 `flutter analyze`；能构建就跑一次 debug 构建。跑不动就如实说明未验证，不要谎报通过。
